# AndVibe

AndVibe is an Android app for cloning a repo, editing it with your own model key, and building it. JavaScript and HTML run on the phone. A project with `gradlew` is compiled on Cloud Run.

The app has five tabs: Console, Files, Git, Vibe, and Build. Console also opens Settings, which shows whether the debug log server is listening.

## Install AndVibe

Open `/Users/b/Projects/Andvibe` in Android Studio, wait for Gradle sync, pick a phone or emulator, and press Run.

From a terminal, with a device or emulator connected:

```bash
cd /Users/b/Projects/Andvibe
./gradlew :app:installDebug
```

That also builds the shell APK and bundles it into the app. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. To build without installing:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`:app:installDebug` is the one to use. A plain `installDebug` also installs the shell module, which is the template named Built app, not AndVibe.

## Console

```text
help                         this list
clear                        clear the screen
pwd    ls [path]    cd [path]
cat <file>    open <file>
projects                     folders already in AndVibe
git clone <https-url> [dir]
git status | add <file> | add .
git commit -m "message"
git push | pull | fetch | log
git diff [file] | branch
git checkout <branch>
git checkout -b <name>
git restore <file> | init
git remote | git remote add origin <url>
compile                      syntax-check JavaScript
test                         run *.test.js and test/
run [file]                   preview HTML or run JS
```

`git clone` uses HTTPS. GitHub, GitLab, Codeberg, and Bitbucket can fall back to a snapshot download if the git protocol fails on the device. SSH remotes are not supported.

`compile` syntax-checks JavaScript. `test` runs `*.test.js`, `*.spec.js`, and files under `test/`. Tests can use `assert(cond, msg)`, `assert.equal(a, b)`, and `assert.strictEqual(a, b)`. `run` opens `index.html` or runs a `.js` file. Relative `require("./file.js")` works. npm packages, `import`/`export`, Python, Rust, and Go do not run on the phone.

## Files and Git

**Projects** lists folders already stored in AndVibe. The last one opens again on the next launch.

**Open** copies a folder from the phone, including `.git`. `node_modules`, `build`, and `.gradle` are skipped. The copy stops at 200MB.

The Git tab is the change list. Tap a file for diff, stage, unstage, discard, or open. Stage all, Commit, Pull, Push, Fetch, Branch, Log, and Init are on that tab.

**Account** stores the git name, email, and an HTTPS token with the other secrets. GitHub wants a personal access token, not the account password.

Vibe and Revise leave the files uncommitted. On the Git tab, tap + to stage a file, or Stage all, then Commit. Message asks the model for a subject and does not commit. The commit list is the history of the current branch. Name, email, HTTPS token, SSH key, and this project's origin are in Settings.

## Vibe

Pick OpenAI, Anthropic, Gemini, Grok, OpenRouter, Cursor, or Custom. The model and base URL start filled in and can be changed. API keys are in Settings. OpenRouter model ids include the vendor, like `anthropic/claude-sonnet-5`.

Every provider except Cursor runs as an agent on the phone. The model calls tools, the phone runs them on the open repo, and the results go back to the model until it finishes, for at most 40 steps. The tools are list, read, grep, find-and-replace edit, write, delete, git status, git diff, the JavaScript checks and tests, and a Cloud Run build when the repo has `gradlew`. Each step shows in the chat. Send turns into Stop while the agent works; Stop takes effect after the current model call returns. If the repo has `AGENTS.md`, `CLAUDE.md`, or `.cursorrules`, the agent reads it first. The model needs tool calling.

Cursor uses a key from the Cursor dashboard. Each send starts a cloud agent with no repository and waits for one reply with whole files, which the phone writes.

| Provider | Default model | Base URL |
| --- | --- | --- |
| OpenAI | `gpt-6.1-sol` | `https://api.openai.com/v1` |
| Anthropic | `claude-sonnet-5` | `https://api.anthropic.com` |
| Gemini | `gemini-3.8-flash` | `https://generativelanguage.googleapis.com/v1beta` |
| Grok | `grok-4.6` | `https://api.x.ai/v1` |
| OpenRouter | `anthropic/claude-sonnet-5` | `https://openrouter.ai/api/v1` |
| Cursor | `composer-2.5` | `https://api.cursor.com` |

Keys stay in encrypted app storage on the phone. A build upload does not include them. **Revise** on the Build tab is what calls the model, and that call goes from the phone straight to the provider.

## Find a repo

On Console, type what you want and tap **Find**. AndVibe searches public repositories on GitHub, GitLab, and Codeberg. With an API key in Settings, the selected model ranks the matches. Tap a result and the Files tab opens as the download starts. If that folder is already on the phone, it opens instead of downloading again.

## Build on the phone

A project without `gradlew` is packed on the phone. **Build APK** syntax-checks and tests the JavaScript, then writes a signed APK named Built app. The path is on the Build tab, under `Android/data/com.example.andvibe/files/apk/`. The first install asks you to allow AndVibe to install unknown apps. Tap **Install** again after that.

Gradle, Java, Kotlin, Python, Rust, and Go are not compiled on the phone.

## Deploy the Cloud Run builder

The builder is `builder/`. The image is JDK 21 plus Android SDK platforms 37 and 36, and build-tools 37.0.0 and 36.0.0. Java and the SDK are downloaded once, when the image is built. A later build does not install them again.

List projects and select one. `PROJECT_ID` is the ID column, not the display name.

```bash
gcloud config get-value account
gcloud projects list
gcloud config get-value project
gcloud config set project PROJECT_ID
```

Create a token once and keep it. It is the only check on `POST /build`. A later deploy should reuse the same token, or the phone's saved token will stop working.

```bash
cd /Users/b/Projects/Andvibe

export BUILD_TOKEN="$(openssl rand -hex 32)"
echo "$BUILD_TOKEN"

gcloud run deploy andvibe-build \
  --source builder \
  --region us-central1 \
  --memory 8Gi \
  --cpu 4 \
  --timeout 3600 \
  --concurrency 1 \
  --max-instances 1 \
  --min-instances 0 \
  --cpu-boost \
  --allow-unauthenticated \
  --set-env-vars "BUILD_TOKEN=${BUILD_TOKEN}"
```

The first deploy is slow because the image downloads the SDK. Source deploy also builds with Cloud Build. The URL stays the same on later deploys.

### Update the builder

Run this from the repo after a change under `builder/`. Leave off `--set-env-vars` so the `BUILD_TOKEN` already on the service stays. Do not generate a new token.

```bash
cd /Users/b/Projects/Andvibe

gcloud run deploy andvibe-build \
  --source builder \
  --region us-central1 \
  --memory 8Gi \
  --cpu 4 \
  --timeout 3600 \
  --concurrency 1 \
  --max-instances 1 \
  --min-instances 0 \
  --cpu-boost \
  --allow-unauthenticated
```

Install the matching app build too. This service streams events and then the APK. An older AndVibe build expects a raw APK.

```bash
./gradlew :app:installDebug
```

The model API key stays on the phone. Do not put it in Secret Manager or in the Cloud Run environment. `BUILD_TOKEN` is only the bearer token that guards `/build`.

Check that the service is up:

```bash
curl -fL "https://SERVICE_URL/healthz"
```

`/healthz` is open. `/build` rejects a missing or wrong bearer token. The service allows unauthenticated HTTP so the phone can call it. The token is what keeps builds private.

Container stdout goes to Cloud Logging. `gcloud run services logs tail` is not in the stable command on this SDK, so the live tail is the beta command. `PROJECT_ID` here is the project id (`marmot-marketing`), not the project number in the service URL.

```bash
gcloud beta run services logs tail andvibe-build --region us-central1 --project marmot-marketing
```

A one-shot read is in the stable command:

```bash
gcloud run services logs read andvibe-build --region us-central1 --project marmot-marketing --limit 80
```

Source deploy prints a Cloud Build log URL. `gcloud builds log` rejects a project number. Pass the project id and the build id from that URL:

```bash
gcloud builds log 357a5ca6-b64c-4b3d-b35a-930044e222d6 --region us-central1 --project marmot-marketing
```

Optional environment variables, comma-separated in `--set-env-vars`:

| Variable | Default | Meaning |
| --- | --- | --- |
| `BUILD_TOKEN` | empty | Bearer token required by `/build`. Empty rejects every build. |
| `BUILD_TIMEOUT` | `3000` | Seconds for one Gradle run. |

The Cloud Run request itself ends at 60 minutes (`--timeout 3600`). The phone stops waiting at 55 minutes. Leave AndVibe open until the APK path appears.

### What a build costs in time

Minimum instances are 0, so an idle service is dropped. The next build pulls the image again. That cold start is often half a minute to a couple of minutes before Gradle runs.

On a fresh instance, the Gradle wrapper and Maven dependencies download into `/home/builder/.gradle`. A second build on that same warm instance reuses the cache. A new instance downloads them again. Sources are deleted after the APK is returned, and Gradle runs with `--no-daemon`, so `build/` output is not kept.

One build runs at a time, on 4 CPUs and 8 GB. It is slower than Android Studio on a laptop, mostly on the first build after the service has gone idle. Setting minimum instances to 1 keeps the Gradle cache and skips the cold start, and you pay for that machine while it sits there.

The upload cap is 32MB. The phone skips `.git`, `build`, `.gradle`, and `local.properties`. Dependencies are downloaded on the server, so the zip should be source plus the wrapper.

### Cloud Run, Compute Engine, Cloud Build

Cloud Run matches the phone: one URL, one request, the APK comes back on that call, and idle cost stays near zero.

Compute Engine is a VM whose disk survives shutdown. The Gradle cache can stay on that disk, so later builds skip the wrapper and library downloads. A stopped VM costs disk. You would run the HTTP server, the firewall, and updates yourself.

Cloud Build is pay-per-minute and can keep a Gradle cache in a bucket. The phone would upload, start a build, poll, then download. It does not return the APK from the build call itself.

### AWS

Stay on Cloud Run for this phone contract. Lambda caps a request at 15 minutes and a body around 6MB. App Runner caps a request at 120 seconds and keeps at least one instance up. ECS Fargate can run the same container, and the load balancer in front of it does not scale to zero. CodeBuild is start, poll, and download, closer to Cloud Build than to one `POST`.

## Use Cloud Run from the app

Rebuild and install AndVibe after a client change:

```bash
cd /Users/b/Projects/Andvibe
./gradlew :app:installDebug
```

Open a project that contains `gradlew`. On the Build tab, paste the service URL (`https://….run.app`, no path) and the build token, then press **Build APK**.

The phone zips the project and posts it to `/build?task=assembleDebug`. Gradle lines stream to the Console and the Build log as they happen. The zip does not contain the model API key.

If Gradle succeeds, the APK comes back and **Install** opens the system installer.

If Gradle fails, the failure stays in the log. Tap **Revise**. That reads the log, calls the model with the key saved in Settings, writes the edited files on the phone, and commits them. Then tap **Build APK** again. Revise does nothing until a build log exists, and it asks for an API key if none is saved.

Deploy the new builder and install the new app together. The response is an event stream followed by the APK bytes. An older app expects a raw APK and will not understand this service.

## Debug logs and MCP

The app keeps a step log for console, build, Cloud Run, git, vibe, import, and local APK packing. Tokens and API keys are not written there. The same lines show up in logcat:

```bash
adb logcat -s AndVibe
```

The app serves that log as an MCP server on the device at `127.0.0.1:8765`. It starts when AndVibe starts, unless **Run MCP server** is unchecked in Console → Settings. That switch is saved and takes effect right away. This repo's `.cursor/mcp.json` points Cursor at `http://127.0.0.1:8765/mcp`.

Cursor is the client on the computer, so the port has to be forwarded from the computer to the device. `adb reverse` listens on the device and blocks the app from binding 8765.

```bash
adb reverse --remove tcp:8765
adb forward tcp:8765 tcp:8765
adb forward --list
```

You want a line like `host-tcp:8765 tcp:8765`. Open AndVibe on the emulator or phone. In the app, Console → Settings should say **Listening on 127.0.0.1:8765**. If it says the port is in use, run the `adb reverse --remove` line above and tap **Try again**.

Then in Cursor, open Settings → MCP (or the command palette, search for MCP) and reload the **andvibe** server. A green dot means Cursor reached the phone. The tools are `logs`, `build_log`, `console_log`, and `state`. `logs` can be filtered by area, such as `cloud`, `cloud.zip`, or `cloud.http`. A request time on the Settings screen means something connected.

`adb forward` has to be set again after the device disconnects. If an emulator is not the only device attached, pass `-s emulator-5554` (or whichever serial `adb devices` shows) on the forward commands.

Wireless debugging is the same ADB connection, so the same forward reaches AndVibe on a phone on the network. Pair from the phone's Wireless debugging screen with the pairing port, then connect with the port on that screen (`adb connect` without a port tries 5555 and is refused). When the emulator is also attached, forward only the phone. Its serial looks like `adb-…._adb-tls-connect._tcp`:

```bash
adb -s adb-41271FDJG0013A-KmdyYN._adb-tls-connect._tcp forward tcp:8765 tcp:8765
```

Open AndVibe on that phone first. Cursor still uses `http://127.0.0.1:8765/mcp`. Run the forward again after the phone disconnects.
