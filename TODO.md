# TODO

## Must have
- cloud resources provisioning
- implement full agent workflow scafolding, testing, virus scan, 

## Should have
- Vulnerability scanner for vibed apps.
- MobSF static scan as a Build tab step: MobSF Docker image on the self-hosted runner like
  the builder, POST each APK to its REST API, show the report next to the VirusTotal result.

## Code quality (later)
- Coverage: `./gradlew koverHtmlReportUnit` → `build/reports/kover/htmlUnit/index.html`.
