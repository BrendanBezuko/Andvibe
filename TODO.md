# TODO

## Bugs (known, confirmed in code audit — all fixed structurally by the rebuild)
- Apps don't reinstall, i have to delete them than install the apk
- Commit button not working unless i spam it (needs blocking maybe)
- commit message btn not working
- when on a stab say board and you press console you can't exit console without leave to a different tab
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

## Style/consistency (`STYLE.md`) — opportunistic, not a phase
Do these when already touching the file, never as standalone rebuild work:
- Add `raised` (#21262D) to `colors.xml`; make the Understand WebView take its palette from
  theme tokens (CSS variables) instead of the ~10 hexes duplicated in `UnderstandDoc`
  (natural moment: Phase 4 Understand step).
- Map the andvibe.org shadcn theme to the app palette (website work, not app work).

## Must have
f- Play Store release checklist: `app.andvibe` application id; Play flavor without
  `REQUEST_INSTALL_PACKAGES`; MCP compiled out of release builds (verify, not just disabled);
  privacy policy + terms linked from the website; closed testing track, then paid testers.

## Should have
- gitleaks (or similar) secret scan before pushing live repos.
- Vulnerability scanner for vibed apps.
- MobSF static scan as a Build tab step: MobSF Docker image on the self-hosted runner like
  the builder, POST each APK to its REST API, show the report next to the VirusTotal result.
- ~~Migrate deprecated APIs: `ApkSigner.SignerConfig.Builder` → `KeyConfig.Jca`;
  `EncryptedSharedPreferences` → KeystorePrefs (one-time ESP migrate).~~
- Self-hosted Cloud Run replacement (not GCP-locked): pick a Docker host that can scale to
  near-zero and stay simple to operate — Coolify first (best Cloud Run-like / complexity
  tradeoff); Knative or K8s+Knative if true scale-to-zero is required; Dokku or plain
  Docker+Caddy only if we accept weaker idle behavior. From the app, deploy/configure
  arbitrary Docker images (builder, MobSF, and user-chosen services) automatically against
  that host — same URL + token contract as today's `/build`, without naming Cloud Run.

## Could have
- ~~Small voice-record app: capture spoken input and turn it into user stories (and other
  requirements) for the Board / backlog.~~

