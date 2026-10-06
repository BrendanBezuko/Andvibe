# AndVibe Architecture Rebuild — Design

Status: proposed · Scope: `app/` module · Companion: the extraction pattern proven by
`ZipWriter`/`BoundedLog` + their tests (write the pure core first, test it, then rewire callers).

## 1. Goals and non-goals

**Goals**

- Every piece of state has one owner, one thread-safety story, and one way to observe it.
- Every long-running operation is cancellable, tracked in one place, and runnable without
  `MainActivity` (so DebugMcp, the foreground service, and future automation can drive it).
- Business logic is unit-testable on the JVM without an emulator.
- `MainActivity` becomes a navigation shell; each tab becomes an independently understandable unit.
- Behavior-preserving: same tabs, same console commands, same build/agent semantics.

**Non-goals (for this rebuild)**

- No Compose migration. The pain is state/orchestration, not the view toolkit. XML + viewBinding
  stays; per-tab Compose becomes *possible* afterwards because each tab will own its state.
- No Gradle module split up front. Package boundaries first; a `:core` module extraction is a
  cheap follow-up once packages are clean.
- No feature changes, no visual redesign.

## 2. Diagnosis (what we're fixing)

Condensed from a full code audit; line references are to the current `main`.

1. **`AppState` is a ~40-field mutable global** mixing domain state, UI state (`tab`,
   `gitMessageClear`, `findNewsTab`), logs, executors, and store initialization. Plain `var`s
   (`vibeResult`, `gitSnapshot`, `find*`, `lastApk`…) are written on worker threads and read on
   main with no synchronization. `AppState.history`/`chat` are unsynchronized mutable collections
   shared across threads.
2. **`UiBridge` is a static 13-callback "re-render everything" listener** implemented only by
   `MainActivity`. Model code (`Agent`, `AgentTools`, `WorkspaceStore.addUse`,
   `BuildHistory.record`, `Console`, `DebugMcp`) calls UI-notification methods directly. Some
   callbacks mutate state: `onVibe` commits the agent result into chat history inside a UI
   callback (MainActivity.kt:312) — if the Activity is gone, the commit never happens.
3. **Two parallel busy-tracking systems**: 10 `@Volatile xxxBusy` flags (check-then-set, racy —
   see the `cloudBuild` tool vs `startBuild` race on `buildBusy`, AgentTools.kt:301) plus the
   `Jobs` registry, merged ad hoc by `AppState.anyBusy()/busyLabel()`.
4. **No cancellation.** Jobs carry no handle to their work. Only the agent and Understand have
   cooperative stop flags. Git, build, clone, import, and search cannot be stopped.
5. **One shared single-thread executor (`AppState.io`)** serializes unrelated work: a 20-minute
   cloud build blocks git refresh, search, and console commands.
6. **Orchestration lives in `MainActivity`** (3,396 lines): `runAgent`, `startBuild`, `runGit`,
   `runUnderstand`, etc. each hand-roll busy flags, Job lifecycle, executor dispatch, error
   handling, and post-processing. None of it is reusable.
7. **Secrets are Activity-owned.** `SecretStore` is constructed in `onCreate` and its values are
   copied into `GitOps`'s global mutable credential vars. Non-Activity code can't reach
   configuration.
8. **Init-order coupling**: stores are `lateinit` objects initialized by `AppState.init`, which is
   called from the Activity (not the Application), and the stores reach back into `AppState`
   (`reposDir`, `appContext`, `vibeBusy`, `chat`).
9. **Implicit workspace scoping**: `ChatStore`, `BuildHistory`, `ApkLibrary` each call
   `WorkspaceStore.current().id` on every access; switching workspace silently swaps what they
   return.

## 3. Target architecture

### 3.1 Layering and package map

Dependencies point strictly downward. Nothing below `features/` knows about Android UI; nothing
below `data/` knows about Android at all (except where a platform API is the point, e.g. SAF).

```
com.example.andvibe
├── core/       Pure JVM, no android.* imports. Unit-tested without Robolectric.
│               RepoFiles, ZipWriter, BoundedLog, ProjectMentions, JsRunner,
│               GitOps, GitClient (JGit is pure JVM), ApkPackager (Context removed:
│               takes template InputStream + output File), Understand scan/pack,
│               FossSearch parsing/ranking, UnderstandDoc.
├── data/       Persistence. Classes (not objects) constructed with explicit paths/prefs.
│               WorkspaceStore, ChatStore, PromptStore, ProjectStore, SecretStore,
│               BuildHistory, ApkLibrary, FossFeed, DebugLog.
├── net/        AiClient, Provider, CloudBuild. Blocking HTTP wrapped in suspend funs.
├── agent/      Agent loop, AgentModel implementations, AgentTools, AgentContext.
├── tasks/      TaskRunner (the one job system), WorkService, Notify.
├── features/   One state holder per tab + orchestration. No View/Activity references.
│               console/, board/, files/, search/, git/, vibe/, understand/, build/,
│               plus workspace/ (workspace switching) and settings/.
├── ui/         MainActivity (shell + nav), one controller per page binding,
│               PreviewActivity, BusyUi, MaxBottomNav, editor widgets.
└── mcp/        DebugMcp, talking to features/ and tasks/, never to ui/.
```

### 3.2 Concurrency model

Adopt **kotlinx-coroutines** (new dependency, plus `kotlinx-coroutines-android`). Rationale:
cancellation and structured concurrency are exactly the two things the current executor model
lacks, and `StateFlow` is the observation primitive the UI layer needs. The two raw executors,
all `Handler.post` relays, and the cooperative stop booleans are replaced.

**Dispatch policy** (preserves today's load-bearing serialization without the global bottleneck):

- `repoDispatcher = Dispatchers.IO.limitedParallelism(1)` — everything that mutates the open
  repo's working tree or git state: console commands, git ops, agent tool execution, import,
  clone, local packaging. Today's correctness depends on `AppState.io` serializing these; we keep
  that guarantee but scope it to repo-mutating work only.
- `Dispatchers.IO` (parallel) — network and read-only work: search, feed refresh, cloud-build
  upload/log streaming, AI calls, release listing. These no longer queue behind a build.
- `Dispatchers.Main` — UI. No more hand-rolled `Handler(Looper.getMainLooper())`.

The agent keeps its dedicated lane: agent model calls run on `Dispatchers.IO`, but its tools hop
to `repoDispatcher`, which also removes the current `cloud_build`-vs-`startBuild` class of race
(single owner, see TaskRunner).

### 3.3 One task system: `TaskRunner`

Replaces **both** the 10 busy flags and the `Jobs` registry.

```kotlin
class TaskRunner(
    private val scope: CoroutineScope,            // app-scoped SupervisorJob
    private val service: WorkServiceController,   // wraps WorkService.sync
    private val notify: Notifier,
) {
    class Task internal constructor(
        val id: Int, val label: String, val tab: Tab,
        val started: Long, internal val job: Job,
        val exclusive: ExclusiveKey?,
    ) { fun cancel() = job.cancel() }

    enum class ExclusiveKey { REPO_WORK, AGENT, BUILD, UNDERSTAND }

    val active: StateFlow<List<Task>>             // drives busy UI + WorkService + notifications

    fun launch(
        label: String, tab: Tab,
        exclusive: ExclusiveKey? = null,          // launching with a held key fails fast
        context: CoroutineContext = EmptyCoroutineContext,
        onDone: (Result<Unit>) -> Unit = {},
        block: suspend CoroutineScope.() -> Unit,
    ): Task
}
```

- **Busy is derived, not stored.** A feature is busy iff it has an active task; the global busy
  label is `active.last().label`. The 10 flags and `anyBusy()/busyLabel()` are deleted.
- **Mutual exclusion is declared, not hand-checked.** `ExclusiveKey.BUILD` makes the
  `cloudBuild`-tool-vs-Build-tab race impossible by construction.
- **Everything is cancellable.** Stop buttons call `task.cancel()`; blocking sections
  (`HttpURLConnection`, JGit) poll `ensureActive()` / check `coroutineContext.isActive` at the
  same granularity as today's agent stop flag ("takes effect after the current call returns" is
  preserved and documented).
- **Foreground-service rule preserved:** `launch` must be called on the main thread while the app
  is foregrounded (same constraint as `Jobs.begin` today, now enforced with a check); the service
  observes `active` and starts/updates/stops itself. `Notify.done` fires from `onDone` when the
  app is invisible — visibility comes from a `ProcessLifecycleOwner`-style flag owned by
  TaskRunner, not a `@Volatile` in `Jobs`.

### 3.4 Feature state holders (the death of `UiBridge`)

One class per tab, app-scoped (not androidx ViewModels — see Decisions), each exposing an
immutable state via `StateFlow` and intents as plain functions:

```kotlin
class GitFeature(
    private val tasks: TaskRunner,
    private val git: GitOps,            // instance now, credentials injected per call
    private val session: ProjectSession,
    private val dispatchers: AppDispatchers,
) {
    data class State(
        val busy: Boolean = false,
        val snapshot: GitOps.Snapshot? = null,
        val detail: String? = null,
        val clearMessage: Boolean = false,
    )
    val state: StateFlow<State>
    fun refresh(); fun stage(path: String); fun commit(message: String); /* … */
}
```

- The UI collects with `repeatOnLifecycle(STARTED)` and renders the whole state. The 13
  `UiBridge` callbacks become per-feature flows; "re-render everything on any change" dies.
- **One-shot UI effects** (open editor, launch preview, scroll-to-bottom) are a small
  `SharedFlow<UiEffect>` per feature — they are *requests to the UI*, never state.
- **State mutations move out of UI callbacks.** The agent result is committed to chat by
  `VibeFeature` when the agent task completes — whether or not an Activity exists. `onVibe`'s
  current behavior (MainActivity.kt:312) is the textbook bug this fixes.
- **Cross-cutting state** gets its own holders, injected where needed:
  - `ProjectSession` — `cwd`, open file, repos dir, workspace-fit rules (today scattered across
    `AppState.cwd/openFile/fitWorkspace/inWorkspace` and the `Console.kt` extension functions).
  - `ConsoleLog` / `BuildLog` — `BoundedLog` wrapped with a `MutableStateFlow<Long>` revision so
    the UI can observe appends cheaply.
  - `UsageMeter` — replaces the `WorkspaceStore.addUse → UiBridge.usageUpdate` hidden UI call.
- The **screenshot bridge** (needed by DebugMcp) stays an explicit narrow interface the Activity
  registers/unregisters — it is genuinely a UI capability, and stays the only one:

  ```kotlin
  interface ScreenshotSource { suspend fun capture(tab: Tab?): Bitmap? }
  ```

### 3.5 Dependency graph: manual `AppGraph`

A single composition root owned by the `Application`:

```kotlin
class AndVibeApp : Application() {
    lateinit var graph: AppGraph; private set
    override fun onCreate() { super.onCreate(); graph = AppGraph(this) }
}

class AppGraph(app: Application) {
    val dispatchers = AppDispatchers()
    val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    val secrets = SecretStore(app)                 // Application-scoped, finally
    val workspaces = WorkspaceStore(File(app.filesDir, "workspaces.json"))
    val session = ProjectSession(app, workspaces)
    val tasks = TaskRunner(scope, WorkServiceController(app), Notifier(app))
    val git = GitOps { secrets.gitCredentials() }  // creds resolved per call, no globals
    // … stores, net, features
    val gitFeature = GitFeature(tasks, git, session, dispatchers)
    // … one per tab
}
```

- No Hilt/Dagger: one file, no annotation processing, constructors are the documentation. The
  activity does `(application as AndVibeApp).graph`.
- Everything `object` + `lateinit init(ctx)` becomes a class constructed here. Init order becomes
  the order of construction — the compiler enforces it.
- **Secrets**: `SecretStore` moves into the graph; `GitOps.authorName/remoteToken` global vars are
  deleted; credentials are read at call time, so Settings edits apply immediately and DebugMcp /
  services can run git and AI operations.

### 3.6 Persistence layer changes

- All stores become classes with constructor-injected files/prefs — directly unit-testable with
  `TemporaryFolder` (as `RepoFilesTest` already does).
- **Explicit workspace scoping**: `ChatStore.load(workspaceId)`, `BuildHistory.list(workspaceId)`,
  `ApkLibrary.list(workspaceId)`. The current "whatever `WorkspaceStore.current()` says right
  now" behavior becomes a parameter passed by the feature that owns workspace switching.
- `ChatStore` loses its reach-ins (`AppState.chat`, `AppState.history`, `vibeBusy` guard): it
  stores and returns data; `VibeFeature` owns the in-memory chat and the "don't swap chats while
  the agent runs" rule.
- Keep the atomic `.tmp` + rename write pattern; keep file formats unchanged (no data migration).

### 3.7 The agent subsystem

- `Agent.run` becomes a `suspend` function taking an `AgentContext` that carries everything it
  needs (repo root, prompts, keys, build config, callbacks) — no `AppState`, no `UiBridge`.
- Progress flows out through the structured channel it already almost has (`onStep`), surfaced by
  `VibeFeature` as state; `agentSteps`'s synchronized-list-capped-at-300 behavior moves into the
  feature.
- `AgentTools.cloudBuild` calls `BuildFeature.startCloudBuild()` (or rather a shared
  `BuildService` both use) under `ExclusiveKey.BUILD` instead of duplicating build logic.
- Model implementations (`AnthropicModel` etc.) are unchanged in logic, moved under `agent/`, and
  get JVM tests with canned HTTP transcripts (inject a `HttpCall` function to fake).

### 3.8 UI shell

- `MainActivity` keeps: binding inflation, nav wiring, overlay show/hide, permission launchers,
  back handling, and the `ScreenshotSource` registration. Target: under ~400 lines.
- One **controller class per page**, constructed in `onCreate` with its page binding + its
  feature: `GitPageController(binding.pageGit, graph.gitFeature, lifecycleScope)`. Controllers
  collect state, render, and forward clicks to feature functions. No fragments needed — the
  include-and-toggle-visibility navigation stays.
- Rotation: nothing to do — features are app-scoped, so state survives exactly as it does today,
  but now by design instead of via the global singleton.

## 4. Key decisions

| Decision | Choice | Alternative considered | Why |
|---|---|---|---|
| Concurrency | Coroutines + StateFlow | Keep executors, add locks | Cancellation and observation are the two missing primitives; executors give neither |
| Serialization of repo work | `limitedParallelism(1)` repo dispatcher | Per-repo `Mutex` | Same guarantee as today's single `io` thread, simplest to reason about; Mutex is the upgrade path if multi-repo parallelism is ever wanted |
| State holders | App-scoped plain classes | androidx ViewModel | Work must outlive the Activity (foreground service model); ViewModel scoping adds a dependency and solves a problem we don't have |
| DI | Manual `AppGraph` | Hilt | ~15 injectables, one developer; annotation processing buys nothing here |
| UI toolkit | Keep XML + controllers | Compose rewrite | Orthogonal to the actual problems; per-tab Compose stays possible later |
| Busy tracking | Derived from TaskRunner | Keep flags, add locking | Flags are redundant once tasks are first-class; deriving removes the race class entirely |
| Modules | Packages now, `:core` later | Multi-module now | Enforce boundaries cheaply first; module split is mechanical once imports are clean |
| UiBridge | Delete; per-feature flows + narrow `ScreenshotSource` | Keep as event bus | 13 coarse callbacks are the root of the "render everything, mutate in callbacks" pattern |

## 5. Migration plan

Strangler pattern: each phase ships, app works throughout, `AppState`/`UiBridge` shrink until
deletable. Phases are ordered so the riskiest rewiring happens when the most logic is already
tested. Each phase lists its exit criteria.

**Phase 0 — done.** `ZipWriter`, `BoundedLog` extracted; `ApkPackager`/`RepoFiles` under test
(31 tests).

**Phase 1 — Foundations (small, mechanical).**
Add coroutines deps. Create `AppGraph` in `AndVibeApp`; move `AppState.init` contents there.
Move `SecretStore` into the graph; delete the `GitOps` credential globals (pass a credentials
provider). Convert `core/` candidates to the package layout; forbid `android.*` imports in
`core/` (a simple lint/grep check in CI or a Gradle verification task).
*Exit:* app boots with graph-constructed stores; `MainActivity` no longer constructs
`SecretStore`; everything still reads `AppState` for the rest.

**Phase 2 — TaskRunner.**
Implement `TaskRunner` + `WorkServiceController`; port `Jobs` callers; convert the launch sites
in `MainActivity` (`startBuild`, `runGit`, `runUnderstand`, `downloadHit`, `startImport`,
`findRepos`, `submitCommand`, `runAgent`) from `io.execute` to `tasks.launch` with exclusive
keys. Busy flags become derived; delete them one at a time as each launch site converts. Add
cancel affordances where stop flags existed (agent, understand) — identical UX.
*Exit:* `Jobs`, `WorkService.sync` call sites, and all 10 busy flags deleted;
`AppState.io/agentIo` deleted; `TaskRunnerTest` covers exclusion, derivation, cancellation,
visibility-gated notifications (JVM, with injected service/notify fakes).

**Phase 3 — Features, one tab at a time.**
Order chosen easiest-first to harden the pattern before the hard ones:
1. **Search/Find** (self-contained state: `find*` fields) → `SearchFeature`.
2. **Understand** → `UnderstandFeature` (stop flag → cancellation).
3. **Build** → `BuildFeature` + shared `BuildService`; `AgentTools.cloudBuild` switches to it
   (race fixed here).
4. **Console** → `ConsoleFeature` + `ConsoleLog`; `Console.run` gets a context parameter
   (session + log + stores) instead of `AppState`; the `AppState` extension functions in
   `Console.kt` move to `ProjectSession`.
5. **Git** → `GitFeature` (biggest UI surface, ~530 lines in MainActivity).
6. **Vibe/Agent** → `VibeFeature` (chat ownership moves here; `onVibe` mutation bug dies;
   `ChatStore` reach-ins removed).
7. **Files, Board, Workspace, Settings** → same pattern, mostly UI state.

Per tab: extract the feature + state, re-point the `UiBridge` callback for that tab to a flow
collection, write JVM tests for the feature's state transitions, then move the rendering code
into a page controller. The corresponding `AppState` fields are deleted at the end of each step
(see §6).
*Exit per tab:* no `AppState` field for that tab remains; its `UiBridge` callback is gone; the
feature has state-transition tests.

**Phase 4 — Shell cleanup.**
Delete `AppState` and `UiBridge` (by now empty except `ScreenshotSource`). `DebugMcp` re-pointed
at features (`consoleLog.text()`, `gitFeature.state.value.snapshot`, `tasks.active`,
`ScreenshotSource`). `Notify` takes tab via parameter. MainActivity is nav + controllers only.
*Exit:* `grep -r "AppState\." app/src` returns nothing; MainActivity < ~400 lines.

**Phase 5 — Optional hardening.**
Extract `core/` into a `:core` pure-JVM Gradle module (tests run without the Android plugin).
Consider per-tab Compose starting with the simplest page (Board). Migrate off deprecated
`EncryptedSharedPreferences`/`ApkSigner.SignerConfig.Builder` constructor.

## 6. State migration map

| `AppState` field(s) | New owner |
|---|---|
| `io`, `agentIo` | `AppDispatchers` + `TaskRunner` |
| `*Busy` flags ×10, `busyLabel`, `anyBusy`, `workBusy` | derived from `TaskRunner.active` |
| `agentStop`, `understandStop` | coroutine cancellation |
| `appContext`, `reposDir`, `init` | `AppGraph`, `ProjectSession` |
| `cwd`, `openFile`, `fitWorkspace`, `inWorkspace` | `ProjectSession` |
| `tab`, `warnedPlain` | `MainActivity` / nav controller (pure UI state) |
| `agentSteps`, `chat`, `history`, `vibeResult`, `writtenPaths`, `vibeRepo` | `VibeFeature` |
| `understandText/Note/Repo` | `UnderstandFeature` |
| `find*` ×5 | `SearchFeature` |
| `lastApk`, build buffer, `loadWorkspaceBuild` | `BuildFeature` + `BuildLog` |
| `gitSnapshot`, `gitDetail`, `gitMessageClear` | `GitFeature` |
| log buffer, `log/text/clear` | `ConsoleLog` |
| `UiBridge` listener + 13 callbacks | per-feature `StateFlow`/`SharedFlow` |
| `UiBridge.screenshot` | `ScreenshotSource` |

## 7. Invariants to preserve (behavioral contract)

1. Foreground service starts only from the foreground, main thread (Android requirement).
2. Completion notifications fire only when the app is not visible.
3. Repo-mutating operations never run concurrently (today: single `io` thread; tomorrow:
   `repoDispatcher` + exclusive keys).
4. Agent Stop takes effect after the current model call returns.
5. The agent result is committed to chat exactly once per run — *strengthened*: now also when no
   Activity exists.
6. Rotation preserves all tab state and running work.
7. On-disk formats (workspaces.json, chats, builds, prefs, secrets) unchanged — no migration.
8. Chat cannot be swapped out from under a running agent.
9. Keys never leave the phone except in provider API calls; build uploads exclude them.

## 8. Risks and mitigations

- **Biggest risk: Phase 3 rewiring of MainActivity render paths.** Mitigation: per-tab, two-step
  (logic first, rendering second); the tab keeps working off the old callback until its feature
  flow is proven; manual smoke script per tab (open, run the tab's main action, rotate, background
  → notification).
- **Cancellation of blocking I/O** (JGit, `HttpURLConnection`) doesn't interrupt mid-call.
  Accepted: identical to today's stop-flag semantics (invariant 4). Document per call site.
- **Hidden sequencing dependencies** on the single `io` thread beyond repo work. Mitigation:
  Phase 2 initially routes *everything* through `repoDispatcher` (behavior-identical), then moves
  network/read-only work to parallel IO one launch site at a time.
- **`ChatStore`/`WorkspaceStore` concurrent access** during the transition window. Mitigation:
  make stores internally thread-safe (they keep their `synchronized`) until their callers are
  single-ownership, then simplify.
- **Regression surface with few UI tests.** Mitigation: feature state-transition tests are cheap
  JVM tests and land *before* each tab's UI rewiring; Espresso smoke test for tab switching +
  console `help` as a safety net.

## 9. Testing strategy

- `core/` and `data/`: plain JUnit + `TemporaryFolder` (pattern already established by
  `ZipWriterTest`, `ApkPackagerTest`, `RepoFilesTest`).
- `tasks/`: `kotlinx-coroutines-test` (`runTest`, virtual time) for exclusion/cancel/derivation.
- `features/`: state-transition tests with faked stores/net (constructor injection makes this
  free); every bug fixed during migration gets a regression test here.
- `agent/`: model adapters tested against canned request/response transcripts via an injected
  HTTP function.
- UI: one Espresso smoke test (launch, visit each tab, run console `help`); beyond that, UI stays
  thin enough that JVM tests carry the coverage.
