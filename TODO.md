# TODO

## Must have
- permissions should be in understand, also dispaly after build
- cloud resources provisioning
- implement full agent workflow scafolding, testing, virus scan, 

## Should have
- gitleaks (or similar) secret scan before pushing live repos.
- Vulnerability scanner for vibed apps.
- MobSF static scan as a Build tab step: MobSF Docker image on the self-hosted runner like
  the builder, POST each APK to its REST API, show the report next to the VirusTotal result.



## Code quality (later)
- Make the store singletons injectable: `WorkspaceStore` (124 call sites), `ChatStore`,
  `BuildHistory`, `ApkLibrary`, `ProjectStore`, `PromptStore`, `RequirementsStore` are global
  `object`s with `init(context)` + `resetForTests()`. Turn each into a class taking a `File`/dir,
  build it in `AppGraph`, and pass it to features. Replace the `Context` that features take only
  for `SharedPreferences` with a small key-value interface. Pilot with `ChatStore` +
  a real `VibeFeatureTest`; then no feature class needs Robolectric to be constructed in a test
  (today none are). `AiClient` can become a class at the same time (its HTTP seam is in place).
- Coverage: `./gradlew koverHtmlReportUnit` → `build/reports/kover/htmlUnit/index.html`.
