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
- No feature changes, no visual redesign. The current look is the contract — tokens and rules
  are documented in `STYLE.md`.

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

```
                 ┌───────────────────────────────────────────┐
                 │ MainActivity — navigation shell           │
                 │ (binding, nav, overlays, permissions)     │
                 └───────────────────┬───────────────────────┘
                                     │ one per tab
                 ┌───────────────────▼───────────────────────┐
                 │ Page controllers — render state,          │
                 │ forward clicks, run one-shot effects      │
                 └───────────────────┬───────────────────────┘
               collect StateFlow ▲   │ call feature functions
                 ┌───────────────┴───▼───────────────────────┐
                 │ Features (one per tab) — own UI state,    │
                 │ orchestrate work, emit effects            │
                 └───────┬───────────────────────┬───────────┘
                         │ shared domain work    │
                 ┌───────▼────────┐    ┌─────────▼──────────┐
                 │ AgentRuntime   │    │ BuildService       │
                 │ loop · models  │    │ (Build tab + the   │
                 │ tools · events │    │  agent build tool) │
                 └───────┬────────┘    └─────────┬──────────┘
                         │                       │
                 ┌───────▼───────────────────────▼───────────┐
                 │ TaskRunner — scheduling, cancellation,    │
                 │ resource locks, foreground service, done  │
                 │ notifications                             │
                 └───────────────────┬───────────────────────┘
                                     │
                 ┌───────────────────▼───────────────────────┐
                 │ net/  AiClient · CloudBuild (HTTP remote) │
│       LocalGradleEngine · Toolchain*      │
                 │ data/ stores (files · prefs · secrets)    │
                 │ core/ pure JVM: RepoFiles · GitOps ·      │
                 │       ZipWriter · JsRunner · Understand   │
                 └───────────────────────────────────────────┘
```

**The governing rule:** UI renders state. Features express intent and own state. Services
perform domain work shared by more than one consumer. TaskRunner manages execution. No layer
reaches upward — the compile-time test is that nothing below `features/` imports from it or
from `ui/`.

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
├── agent/      AgentRuntime: loop, AgentModel implementations, ToolRegistry,
│               AgentContext, AgentEvent stream (§3.7).
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
  that guarantee but scope it to repo-mutating work only. Note this single lane intentionally
  serializes across *different* repos too — an accepted Phase 2 simplification identical to
  today's behavior; a per-root `Mutex`/dispatcher is the upgrade path if multi-repo parallelism
  is ever wanted (see Decisions).
- `Dispatchers.IO` (parallel) — network and read-only work: search, feed refresh, cloud-build
  upload/log streaming, AI calls, release listing. These no longer queue behind a build.
- `Dispatchers.Main` — UI. No more hand-rolled `Handler(Looper.getMainLooper())`.

The agent keeps its dedicated lane: agent model calls run on `Dispatchers.IO`, but its tools hop
to `repoDispatcher`, which also removes the current `cloud_build`-vs-`startBuild` class of race
(single owner, see TaskRunner).

### 3.3 One task system: `TaskRunner`

Replaces **both** the 10 busy flags and the `Jobs` registry. TaskRunner is a **scheduler, not a
domain object**: it knows about jobs, cancellation, lifecycle, and resource locks. It does not
know what "build" or "agent" means — those semantics live in the features/services that declare
the resources they hold.

```kotlin
class TaskRunner(
    private val scope: CoroutineScope,            // app-scoped SupervisorJob
    private val service: WorkServiceController,   // wraps WorkService.sync
    private val notify: Notifier,
) {
    class Task internal constructor(
        val id: Int, val label: String, val tab: Tab,
        val started: Long, internal val job: Job,
        val holds: Set<Resource>,
    ) { fun cancel() = job.cancel() }

    val active: StateFlow<List<Task>>             // drives busy UI + WorkService + notifications

    fun launch(
        label: String, tab: Tab,
        holds: Set<Resource> = emptySet(),        // fails fast if any is already held
        context: CoroutineContext = EmptyCoroutineContext,
        onDone: (Result<Unit>) -> Unit = {},
        block: suspend CoroutineScope.() -> Unit,
    ): Task
}

@JvmInline value class Resource(val key: String)  // opaque to the scheduler
```

Callers define their own resource tokens, keyed by what they actually contend on:

```kotlin
fun repo(root: File)  = Resource("repo:${root.canonicalPath}")
fun build(root: File) = Resource("build:${root.canonicalPath}")
fun agent()           = Resource("agent")
```

- **Busy is derived, not stored.** A feature is busy iff it has an active task; the global busy
  label is `active.last().label`. The 10 flags and `anyBusy()/busyLabel()` are deleted.
- **Mutual exclusion is declared, not hand-checked.** `BuildService` claims `build(root)` whether
  invoked from the Build tab or the agent's `cloud_build` tool, so that race is impossible by
  construction — and because the tokens are per-repo strings rather than app-wide enum values,
  supporting parallel work across repos/workspaces later requires no scheduler change.
- **Everything is cancellable.** Stop buttons call `task.cancel()`; blocking sections
  (`HttpURLConnection`, JGit) poll `ensureActive()` / check `coroutineContext.isActive` at the
  same granularity as today's agent stop flag ("takes effect after the current call returns" is
  preserved and documented).
- **Foreground-service rule preserved:** `launch` must be called on the main thread while the app
  is foregrounded (same constraint as `Jobs.begin` today, now enforced with a check); the service
  observes `active` and starts/updates/stops itself. `Notify.done` fires from `onDone` when the
  app is invisible — visibility comes from a `ProcessLifecycleOwner`-style flag owned by
  TaskRunner, not a `@Volatile` in `Jobs`.
- **Service promotion can still lose the race** on Android 12+: if the user hits Home between
  `launch` and the service start, `startForegroundService` throws
  `ForegroundServiceStartNotAllowedException`. `WorkServiceController` catches it and degrades
  gracefully: the task **keeps running** (coroutines don't need the service — it only buys
  process priority and the wakelock), and the controller re-attempts promotion the next time the
  app is foregrounded while tasks are active. This is a latent crash in today's
  `Jobs.begin → WorkService.sync` path, fixed by construction here.

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
- **One-shot UI effects** (open editor, launch preview, scroll-to-bottom) are a sealed type per
  feature, backed by a **`Channel(BUFFERED)` exposed as `channel.receiveAsFlow()`** — not a
  `SharedFlow`. A `SharedFlow` with no collector drops the emission, so an effect fired while the
  Activity is stopped (an agent finishing in the background and requesting an editor open) would
  vanish; a buffered channel holds it and delivers exactly once when collection resumes. Today's
  `UiBridge` has precisely this drop bug (`listener == null` → the post is lost). Effects are
  *requests to the UI*, never state. The litmus test: **state can be re-rendered at any time; an
  effect must happen exactly once.** Anything that would misbehave if replayed on rotation is an
  effect.

  ```kotlin
  sealed interface GitEffect {
      data class OpenFile(val file: File) : GitEffect
      data class ShowError(val message: String) : GitEffect
      data object ClearMessageField : GitEffect   // today's gitMessageClear flag, done right
  }
  ```
- **State mutations move out of UI callbacks.** The agent result is committed to chat by
  `VibeFeature` when the agent task completes — whether or not an Activity exists. `onVibe`'s
  current behavior (MainActivity.kt:312) is the textbook bug this fixes.
- **Cross-cutting state** gets its own holders, injected where needed:
  - `ProjectSession` — `cwd`, open file, repos dir, workspace-fit rules (today scattered across
    `AppState.cwd/openFile/fitWorkspace/inWorkspace` and the `Console.kt` extension functions).
    **Switch policy:** changing project or workspace is *refused* while any task holds
    `repo(currentRoot)` or `agent()` — the switch checks TaskRunner and fails with the blocking
    task's label. This makes invariant 8 structural, replacing `ChatStore`'s current
    "refuse while `vibeBusy`" guard. (The agent's own `create_project` tool is the one sanctioned
    root change, and it runs *inside* the agent's task.)
  - `ConsoleLog` / `BuildLog` — `BoundedLog` wrapped with a `MutableStateFlow<Long>` revision so
    the UI can observe appends cheaply.
  - `UsageMeter` — replaces the `WorkspaceStore.addUse → UiBridge.usageUpdate` hidden UI call.
- The **screenshot bridge** (needed by DebugMcp) stays an explicit narrow interface the Activity
  registers/unregisters — it is genuinely a UI capability, and stays the only one:

  ```kotlin
  interface ScreenshotSource { suspend fun capture(tab: Tab?): Bitmap? }
  ```

  Registered in `onStart`, unregistered in `onStop`, so the graph never holds a reference to a
  stopped Activity.

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

### 3.7 AgentRuntime — a subsystem, not a feature

The agent is the core of the product; the Vibe tab is merely its primary UI. Today its progress
reporting is three bespoke paths (an `onStep` callback that mutates a global list, direct
`UiBridge` calls from inside the loop, and DebugMcp reading globals). The runtime makes it one:

```
agent/
├── AgentRuntime    entry point: start(request): AgentRun — at most one run (Resource("agent"))
├── AgentLoop       plan/execute phases (today's Agent.run) as a suspend fun; cancellable
├── AgentModel      AnthropicModel · OpenAiModel · ResponsesModel · GeminiModel (logic unchanged)
├── ToolRegistry    List<Tool> where Tool = { name, schema, suspend execute(ctx, args) } —
│                   replaces the monolithic dispatch in AgentTools; tools become individually
│                   testable, and per-tool permissions become a list filter later
├── AgentContext    repo root, session, prompts, keys, services — no AppState, no UiBridge
└── AgentEvent      sealed progress stream replacing the onStep string callback
```

```kotlin
sealed interface AgentEvent {
    data class Step(val text: String) : AgentEvent
    data class ToolCall(val name: String, val summary: String) : AgentEvent
    data class FilesChanged(val paths: List<String>) : AgentEvent
    data class Usage(val tokens: Long, val cost: Double) : AgentEvent
    data class Done(val result: String, val writtenPaths: List<String>) : AgentEvent
    data class Failed(val message: String) : AgentEvent
}

class AgentRun(val events: Flow<AgentEvent>, private val task: TaskRunner.Task) {
    fun cancel() = task.cancel()
}
```

- **The runtime owns its exclusivity.** `start` claims `Resource("agent")` via TaskRunner
  *itself* — callers (Vibe, DebugMcp, future automation) cannot accidentally start a second run.
  A second `start` while one is active **fails fast**, returning the running run's label; it does
  not queue and does not cancel the previous run (matches today's UX, where Send becomes Stop).
- `VibeFeature` folds the event stream into chat state (including the commit-to-`ChatStore` on
  `Done` — no Activity required). DebugMcp and the done-notification consume the *same* stream.
- The `cloud_build` tool calls the shared `BuildService` under `Resource("build:$root")` — the
  same token the Build tab holds — instead of duplicating the build path.
- Model adapters get JVM tests with canned HTTP transcripts (inject the HTTP call as a function).
- **Explicitly not built now:** agent memory, run replay, multi-agent, evaluation harnesses.
  Those are speculative; the event stream and the tool registry are precisely the two extension
  points they would need, and having the seams is enough.

### 3.8 UI shell

- `MainActivity` keeps: binding inflation, nav wiring, overlay show/hide, permission launchers,
  back handling, and the `ScreenshotSource` registration. Target: under ~400 lines.
- One **controller class per page**, constructed in `onCreate` with its page binding + its
  feature: `GitPageController(binding.pageGit, graph.gitFeature, lifecycleScope)`. Controllers
  collect state, render, and forward clicks to feature functions. No fragments needed — the
  include-and-toggle-visibility navigation stays.
- Rotation: nothing to do — features are app-scoped, so state survives exactly as it does today,
  but now by design instead of via the global singleton.
- **Process death is not rotation.** App-scoped state dies when Android kills the backgrounded
  process. That is already true today: the current app rebuilds from its persisted stores
  (`ProjectStore` restores the last project, `ChatStore` the conversation, `BuildHistory` the
  last build log) and loses the rest. The contract here is **parity, made explicit**: every
  feature must initialize its state from its store, never assume a warm start. Restoring purely
  transient UI (active tab, open editor file) via `SavedStateHandle`/Bundle is a deliberate
  non-goal for the rebuild. One cheap exception: **unsent draft text** (Vibe prompt, commit
  message) is a daily papercut worth persisting via the existing stores — do it when touching
  those features in Phase 4, not with `SavedStateHandle` machinery.

### 3.9 Vocabulary

Four words, used consistently in code and in this document:

| Word | Meaning | Lives in | Replayable? |
|---|---|---|---|
| **State** | current truth, renderable at any moment | `StateFlow` per feature | yes — rendering it twice is harmless |
| **Effect** | something the UI must do exactly once | `Channel(BUFFERED)` → `receiveAsFlow()` per feature | no |
| **Event** | something that happened (agent step, tool call) | `Flow<AgentEvent>` etc. | consumed once, *persisted* when it's history |
| **Task** | work currently executing | `TaskRunner.active` | n/a — cancellable |

`StateFlow` is never the historical record. History that matters is already persisted —
`ChatStore` (conversations), `BuildHistory` (builds + logs), `DebugLog` (diagnostics) — and the
features write to those stores from event streams, not from UI callbacks.

## 4. Key decisions

| Decision | Choice | Alternative considered | Why |
|---|---|---|---|
| Concurrency | Coroutines + StateFlow | Keep executors, add locks | Cancellation and observation are the two missing primitives; executors give neither |
| Serialization of repo work | `limitedParallelism(1)` repo dispatcher | Per-repo `Mutex` | Same guarantee as today's single `io` thread, simplest to reason about; Mutex is the upgrade path if multi-repo parallelism is ever wanted |
| State holders | App-scoped plain classes | androidx ViewModel | Work must outlive the Activity (foreground service model); ViewModel scoping adds a dependency and solves a problem we don't have |
| DI | Manual `AppGraph` | Hilt | ~15 injectables, one developer; annotation processing buys nothing here |
| UI toolkit | Keep XML + controllers | Compose rewrite | Orthogonal to the actual problems; per-tab Compose stays possible later |
| Busy tracking | Derived from TaskRunner | Keep flags, add locking | Flags are redundant once tasks are first-class; deriving removes the race class entirely |
| Exclusion | Opaque per-repo resource tokens | Domain enum in the scheduler | Keeps TaskRunner domain-agnostic; multi-repo parallelism later costs nothing |
| Feature API | Plain functions | MVI `dispatch(sealed Intent)` | Functions *are* intents and are equally JVM-testable; sealed-intent ceremony only pays off with middleware (logging/replay) we don't need |
| Agent | First-class runtime with event stream + tool registry | Agent as just another feature | Three consumers (Vibe UI, DebugMcp, notifications) need the same progress stream; the product's core deserves its own boundary |
| Services layer | Only where ≥2 consumers share it (AgentRuntime, BuildService) | A Service per feature | A `GitService` with one caller is indirection; features are the orchestration layer |
| Modules | Packages now, `:core` later | Multi-module now | Enforce boundaries cheaply first; module split is mechanical once imports are clean |
| UiBridge | Delete; per-feature flows + narrow `ScreenshotSource` | Keep as event bus | 13 coarse callbacks are the root of the "render everything, mutate in callbacks" pattern |

## 5. Migration plan

Strangler pattern: each phase ships, app works throughout, `AppState`/`UiBridge` shrink until
deletable. Phases are ordered so the riskiest rewiring happens when the most logic is already
tested. Each phase lists its exit criteria.

**Phase 0 — done.** `ZipWriter`, `BoundedLog` extracted; `ApkPackager`/`RepoFiles` under test
(31 tests).

**Phase 1 — Foundations: AppGraph + ProjectSession (small, mechanical).**
Add coroutines deps. Create `AppGraph` in `AndVibeApp`; move `AppState.init` contents there.
Move `SecretStore` into the graph; delete the `GitOps` credential globals (pass a credentials
provider). Create **`ProjectSession`** now — it owns `cwd`, `openFile`, the repos dir, and the
workspace-fit rules (including the `AppState` extension functions currently defined in
`Console.kt`); `AppState.cwd`/`openFile` become delegating properties during the transition so
existing call sites keep compiling. While moving each store, verify it needs only an Application
context (current `AppState.init` already passes `applicationContext`, and the stores use
files/prefs only — but check each; anything UI-flavored stays with the Activity).
Convert `core/` candidates to the package layout; forbid
`android.*` imports in `core/` (a simple grep/lint check).
*Exit:* app boots with graph-constructed stores; `MainActivity` no longer constructs
`SecretStore`; project/workspace scoping has one owner; everything else still reads `AppState`.

**Phase 2 — TaskRunner.**
Implement `TaskRunner` + `WorkServiceController`; port `Jobs` callers; convert the launch sites
in `MainActivity` (`startBuild`, `runGit`, `runUnderstand`, `downloadHit`, `startImport`,
`findRepos`, `submitCommand`, `runAgent`) from `io.execute` to `tasks.launch`, each declaring
the resources it holds. Busy flags become derived; delete them one at a time as each launch site
converts. Add cancel affordances where stop flags existed (agent, understand) — identical UX.
*Exit:* `Jobs`, `WorkService.sync` call sites, and all 10 busy flags deleted;
`AppState.io/agentIo` deleted; `TaskRunnerTest` covers resource exclusion, busy derivation,
cancellation, visibility-gated notifications, **and the promotion-degrade path** — a fake service
controller that throws `ForegroundServiceStartNotAllowedException` must leave the task running
and get a promotion retry on the next foreground signal (JVM, with injected service/notify
fakes).

**Phase 3 — AgentRuntime + BuildService.**
Pulled ahead of the tab migrations because the agent is the architecturally central subsystem
and two later steps depend on it. Extract `BuildService` (cloud + local packaging paths shared
by the Build tab and the agent). Restructure `agent/`: `AgentLoop` as a suspend function,
`ToolRegistry` replacing the `AgentTools` dispatch, `AgentContext` without `AppState`/`UiBridge`,
and the `AgentEvent` stream replacing `onStep` + the direct `UiBridge` calls. The `cloud_build`
tool switches to `BuildService` (the `buildBusy` race dies here). The Vibe UI is *not* migrated
yet — MainActivity temporarily adapts the event stream to its existing render calls.
**Dual-path budget:** that adapter is *one file*, it adds no new `UiBridge` callbacks, and it is
deleted in the same change that lands `VibeFeature` (Phase 4 step 6). Temporary bridges that
outlive their phase become a second architecture.
*Exit:* `agent/` has no `AppState`/`UiBridge` references; tool and model-adapter JVM tests exist;
one build path; agent cancellable via its task.

**Phase 4 — Features, one tab at a time.**
Order chosen easiest-first to harden the pattern before the big ones:
1. **Search/Find** (self-contained state: `find*` fields) → `SearchFeature`.
2. **Understand** → `UnderstandFeature` (stop flag → cancellation).
3. **Build** → `BuildFeature` over the existing `BuildService` (state + history UI only).
4. **Console** → `ConsoleFeature` + `ConsoleLog`; `Console.run` gets a context parameter
   (session + log + stores) instead of `AppState`.
5. **Git** → `GitFeature` (biggest UI surface, ~530 lines in MainActivity).
6. **Vibe** → `VibeFeature` folds `AgentEvent`s into chat state (chat ownership moves here; the
   `onVibe` mutation bug dies; `ChatStore` reach-ins removed).
7. **Files, Board, Workspace, Settings** → same pattern, mostly UI state.

Per tab: extract the feature + state, re-point the `UiBridge` callback for that tab to a flow
collection, write JVM tests for the feature's state transitions, then move the rendering code
into a page controller. The corresponding `AppState` fields are deleted at the end of each step
(see §6).
*Exit per tab:* no `AppState` field for that tab remains; its `UiBridge` callback is gone; the
feature has state-transition tests; **every non-UI reader of the deleted fields (`DebugMcp`,
`Notify`) is re-pointed at the feature in the same step** — MCP stays green through the whole
strangler, not just after Phase 5; and the per-tab smoke pass (open → main action → rotate →
background → notification → MCP responds) is run.

**Phase 5 — Shell cleanup.**
Delete `AppState` and `UiBridge` (by now empty except `ScreenshotSource`). `DebugMcp` re-pointed
at features (`consoleLog.text()`, `gitFeature.state.value.snapshot`, `tasks.active`,
`ScreenshotSource`, `AgentRuntime` events). `Notify` takes tab via parameter. MainActivity is
nav + controllers only.
*Exit:* `grep -r "AppState\." app/src` returns nothing; no `ui/` imports under `mcp/`;
MainActivity < ~400 lines.

**Phase 6 — Optional hardening.**
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
| `UiBridge` listener + 13 callbacks | per-feature `StateFlow` + effect `Channel` |
| `UiBridge.screenshot` | `ScreenshotSource` |

## 7. Invariants to preserve (behavioral contract)

1. Foreground service starts only from the foreground, main thread (Android requirement).
2. Completion notifications fire only when the app is not visible.
3. Repo-mutating operations never run concurrently (today: single `io` thread; tomorrow:
   `repoDispatcher` + exclusive keys).
4. Agent Stop takes effect after the current model call returns.
5. The agent result is committed to chat exactly once per run — *strengthened*: now also when no
   Activity exists.
6. Rotation preserves all tab state and running work. After process death, the app restores
   everything it restores today (last project, chats, build history) by initializing features
   from their stores — no regression, no new guarantees.
7. On-disk formats (workspaces.json, chats, builds, prefs, secrets) unchanged — no migration.
8. Chat cannot be swapped out from under a running agent — enforced structurally: project and
   workspace switches are refused while `repo(currentRoot)` or `agent()` is held (§3.4).
9. Keys never leave the phone except in provider API calls; build uploads exclude them.

## 8. Risks and mitigations

- **Biggest risk: Phase 4 rewiring of MainActivity render paths.** Mitigation: per-tab, two-step
  (logic first, rendering second); the tab keeps working off the old callback until its feature
  flow is proven; manual smoke script per tab (open, run the tab's main action, rotate, background
  → notification).
- **Cancellation of blocking I/O** (JGit, `HttpURLConnection`) doesn't interrupt mid-call.
  Accepted: identical to today's stop-flag semantics (invariant 4). Rule for Phases 2–3: every
  blocking call site either polls `ensureActive()` at the same points the old stop flags were
  checked, or carries an explicit `// not cancellable mid-call` comment — otherwise implementers
  will assume `cancel()` interrupts the call and ship silent non-cancellation.
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
