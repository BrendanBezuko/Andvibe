# TODO

## Bugs (known, confirmed in code audit — all fixed structurally by the rebuild)
- `buildBusy` race: the agent's `cloud_build` tool and the Build tab check-then-set the flag
  from different executors (AgentTools.kt vs MainActivity) → rebuild Phase 3 (`BuildService` +
  resource lock).
- Agent result is committed to chat inside a UI callback (`onVibe`), so it's lost if the
  Activity is gone when the agent finishes → rebuild Phase 4 (Vibe).
- `UiBridge` drops notifications when `listener == null` (backgrounded/rotating) → rebuild
  Phase 4 (effects become buffered channels).
- Foreground-service start crashes (`ForegroundServiceStartNotAllowedException`) if the user
  backgrounds the app in the window between starting work and `WorkService.sync` → rebuild
  Phase 2 (catch + degrade + re-promote).
- Unsynchronized cross-thread state: `gitSnapshot`, `vibeResult`, `find*`, `AppState.history`,
  `AppState.chat` → rebuild Phases 2–4.

## Now — architecture rebuild (`DESIGN.md`)
- [x] Phase 0: extract pure core (`ZipWriter`, `BoundedLog`) + 27 JVM tests
- [x] Phase 1: `AppGraph` + `ProjectSession`; `SecretStore` app-scoped; kill `GitOps` globals
- [x] Phase 2: `TaskRunner` (coroutines, cancellation, resource locks, busy derived)
- [x] Phase 3: `AgentRuntime` (event stream, `ToolRegistry`) + shared `BuildService`
- [ ] Phase 4: per-tab features (Search → Understand → Build → Console → Git → Vibe → rest);
      persist draft text (Vibe prompt, commit message) when touching those tabs
- [ ] Phase 5: delete `AppState`/`UiBridge`; MainActivity → nav shell
- [ ] Phase 6: hardening (`:core` module, deprecation migrations below)

Per phase/tab smoke pass before checking the box: open → main action → rotate → background →
notification fires → MCP still responds. The rebuild's biggest risk is silent behavior drift,
not package layout. Nothing from Should/Could/Roadmap starts until Phase 5 is done.

## Style/consistency (`STYLE.md`) — opportunistic, not a phase
Do these when already touching the file, never as standalone rebuild work:
- Add `raised` (#21262D) to `colors.xml`; make the Understand WebView take its palette from
  theme tokens (CSS variables) instead of the ~10 hexes duplicated in `UnderstandDoc`
  (natural moment: Phase 4 Understand step).
- Map the andvibe.org shadcn theme to the app palette (website work, not app work).

## Must have
- Easy fork workflow: fork a repo and work privately against my own remote.
- Play Store release checklist: `app.andvibe` application id; Play flavor without
  `REQUEST_INSTALL_PACKAGES`; MCP compiled out of release builds (verify, not just disabled);
  privacy policy + terms linked from the website; closed testing track, then paid testers.

## Should have
- gitleaks (or similar) secret scan before pushing live repos.
- Vulnerability scanner for vibed apps.
- MobSF static scan as a Build tab step: MobSF Docker image on the self-hosted runner like
  the builder, POST each APK to its REST API, show the report next to the VirusTotal result.
- Migrate deprecated APIs: `ApkSigner.SignerConfig.Builder` constructor (ApkPackager.kt:47),
  `EncryptedSharedPreferences` (androidx.security-crypto is deprecated upstream).
- Self-hosted Cloud Run replacement (not GCP-locked): pick a Docker host that can scale to
  near-zero and stay simple to operate — Coolify first (best Cloud Run-like / complexity
  tradeoff); Knative or K8s+Knative if true scale-to-zero is required; Dokku or plain
  Docker+Caddy only if we accept weaker idle behavior. From the app, deploy/configure
  arbitrary Docker images (builder, MobSF, and user-chosen services) automatically against
  that host — same URL + token contract as today's `/build`, without naming Cloud Run.


## Could have
- Voice agent.
- RAG-index the open codebase for the agent (open question — decide if it earns its keep).

## Roadmap
- Hybrid expert agent: the local agent is only a start. Move to a hybrid expert system and
  optimize token use; this is the platform for fine-tuning how the agent works.
- CVE/CWE discovery: local and/or remote scanning (architecture TBD) so a vibed app can be
  checked for threats to the phone, complementing Play Protect.
- UX: improve the overall experience across the app (keep `STYLE.md` as the contract).
- Caching and performance: Redis or other caching where it fits.
- Audio first: the whole workflow by voice — phone + AirPods as good as a laptop, e.g. while
  walking the dog. Nothing limits this to Android apps.
