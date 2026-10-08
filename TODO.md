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

## Must have
- permissions should be in understand, also dispaly after build
- cloud resources provisioning, testing, virus scan, explcitly stated in the agent
- 


## Should have
- gitleaks (or similar) secret scan before pushing live repos.
- Vulnerability scanner for vibed apps.
- MobSF static scan as a Build tab step: MobSF Docker image on the self-hosted runner like
  the builder, POST each APK to its REST API, show the report next to the VirusTotal result.

## Could have


