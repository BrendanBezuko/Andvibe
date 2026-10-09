package com.example.andvibe

import com.example.andvibe.core.JsRunner
import com.example.andvibe.core.LocalGradleEngine
import com.example.andvibe.tasks.Res
import com.example.andvibe.tasks.TaskRunner
import android.content.Context
import java.io.File

/**
 * Single build path for the Build tab and the agent's build tool
 * (DESIGN.md §3.7 / Phase 3). Mutual exclusion is Res.BUILD — claimed by
 * TaskRunner.launch for the tab, or nest-claimed here when the agent calls in.
 *
 * Gradle projects: local toolchain by default. Remote (BYOC) only when
 * Variables → Prefer remote builder is on (or [Mode.REMOTE_ONLY]).
 * Non-Gradle: JsRunner + ApkPackager on device.
 */
class BuildService(
    private val app: Context,
    private val tasks: TaskRunner,
    private val buildLog: com.example.andvibe.features.build.BuildLog? = null,
    private val onBuildChanged: (() -> Unit)? = null,
    private val preferRemote: () -> Boolean = { false },
) {
    enum class Mode {
        /** gradlew → local Gradle, or remote only if preferred in settings; else JS pack. */
        AUTO,
        /** Agent / explicit: Gradle only (local, unless prefer-remote is on). */
        GRADLE_ONLY,
        /** Force remote builder; errors if no gradlew or no URL/token. */
        REMOTE_ONLY,
    }

    data class Outcome(
        val title: String,
        val summary: String,
        val apk: File?,
        /** True when the APK came from the remote builder. */
        val remote: Boolean,
    )

    /**
     * @param nestClaim claim Res.BUILD for the duration (agent tool). False when
     *   the caller already holds BUILD via TaskRunner.launch.
     */
    fun run(
        root: File,
        buildUrl: String,
        buildToken: String,
        mode: Mode = Mode.AUTO,
        nestClaim: Boolean = false,
        log: (String) -> Unit,
    ): Outcome {
        if (nestClaim && !tasks.tryClaim(Res.BUILD)) error("a build is already running")
        val started = System.currentTimeMillis()
        val captured = StringBuilder()
        val sink: (String) -> Unit = { line ->
            log(line)
            synchronized(captured) {
                captured.append(line).append('\n')
                if (captured.length > 200_000) captured.delete(0, captured.length - 120_000)
            }
        }
        var built: File? = null
        var summary = ""
        var title = "Build failed"
        var remote = false
        val hasGradle = File(root, "gradlew").isFile
        try {
            DebugLog.step("build", "start path=${root.absolutePath} gradlew=$hasGradle mode=$mode")
            when {
                mode == Mode.REMOTE_ONLY -> {
                    if (!hasGradle) error("this repo has no gradlew, so the remote builder cannot build it")
                    built = remoteBuild(root, buildUrl, buildToken, sink, requireRemote = true)
                    remote = true
                    title = "Build ready"
                    summary = agentOrTabSummary(mode, root, built)
                }
                hasGradle -> {
                    val outcome = gradleBuild(root, buildUrl, buildToken, mode, sink)
                    built = outcome.first
                    remote = outcome.second
                    if (built != null) {
                        title = "Build ready"
                        summary = agentOrTabSummary(mode, root, built)
                    } else {
                        title = "Build needs setup"
                        summary = outcome.third
                    }
                }
                mode == Mode.GRADLE_ONLY -> {
                    error("this repo has no gradlew")
                }
                else -> {
                    DebugLog.step("build", "mode=js-pack")
                    sink(JsRunner.detect(root))
                    sink("")
                    sink(JsRunner.compile(root))
                    sink("")
                    sink(JsRunner.test(root))
                    sink("")
                    val apk = ApkPackager.packageApk(app, root, sink)
                    built = apk
                    title = "Build ready"
                    summary = "${root.name}: ${apk.name}"
                }
            }
            if (built != null) {
                sink("")
                sink("APK")
                sink(built.absolutePath)
                if (mode == Mode.AUTO) {
                    sink(
                        when {
                            remote -> "Tap Install."
                            hasGradle -> "Tap Install."
                            else -> "Tap Install. The new app is named Built app."
                        }
                    )
                }
            }
        } catch (t: Throwable) {
            DebugLog.step("build", "fail ${t.javaClass.simpleName}: ${t.message}")
            val message = t.message ?: t.javaClass.simpleName
            if (mode == Mode.GRADLE_ONLY || mode == Mode.REMOTE_ONLY) {
                summary = "Agent build failed: $message"
                sink("build failed: $message")
                throw t
            }
            sink("build failed: $message")
            summary = message
        } finally {
            val logText = synchronized(captured) { captured.toString() }
            BuildHistory.record(
                root.name,
                started,
                built != null,
                built?.name,
                summary,
                logText,
            )
            if (nestClaim) tasks.release(Res.BUILD)
        }
        return Outcome(title, summary, built, remote)
    }

    /**
     * Agent build tool entry. Nest-claims BUILD, optionally mirrors into [buildLog],
     * and returns the tool result string.
     */
    fun agentBuild(
        root: File,
        buildUrl: String,
        buildToken: String,
        buildLog: com.example.andvibe.features.build.BuildLog? = null,
    ): String {
        buildLog?.clear()
        val local = StringBuilder()
        val note: (String) -> Unit = { line ->
            buildLog?.append(line)
            synchronized(local) {
                local.append(line).append('\n')
                if (local.length > 200_000) local.delete(0, local.length - 120_000)
            }
        }
        note("Agent build for ${root.name}.")
        return try {
            val outcome = run(
                root = root,
                buildUrl = buildUrl,
                buildToken = buildToken,
                mode = Mode.GRADLE_ONLY,
                nestClaim = true,
                log = note,
            )
            if (buildLog != null) onBuildChanged?.invoke()
            val apk = outcome.apk ?: error(outcome.summary)
            "BUILD SUCCESSFUL\nAPK: ${apk.name}\n\n" + tail(synchronized(local) { local.toString() }, 3_000)
        } catch (t: Throwable) {
            if (buildLog != null) onBuildChanged?.invoke()
            val message = t.message ?: t.javaClass.simpleName
            "BUILD FAILED: $message\n\n" + tail(synchronized(local) { local.toString() }, 14_000)
        }
    }

    @Deprecated("Use agentBuild", ReplaceWith("agentBuild(root, buildUrl, buildToken, buildLog)"))
    fun agentCloudBuild(
        root: File,
        buildUrl: String,
        buildToken: String,
        buildLog: com.example.andvibe.features.build.BuildLog? = null,
    ): String = agentBuild(root, buildUrl, buildToken, buildLog)

    /**
     * @return Triple(apk, remote, setupMessageIfNoApk)
     */
    private fun gradleBuild(
        root: File,
        buildUrl: String,
        buildToken: String,
        mode: Mode,
        sink: (String) -> Unit,
    ): Triple<File?, Boolean, String> {
        val forceRemote = preferRemote() || mode == Mode.REMOTE_ONLY
        if (forceRemote) {
            DebugLog.step("build", "mode=remote (preferred)")
            sink("Using remote builder (preferred in Variables).")
            val apk = remoteBuild(root, buildUrl, buildToken, sink, requireRemote = true)
            return Triple(apk, true, "")
        }

        val bootstrap = ToolchainBootstrap(app)
        var st = bootstrap.status()
        if (!st.ready) {
            sink("Local toolchain not ready — installing…")
            try {
                st = bootstrap.ensure(sink, promptCompanionInstall = true)
            } catch (t: Throwable) {
                DebugLog.step("build", "toolchain ensure failed: ${t.message}")
                sink(t.message ?: "toolchain install failed")
                return Triple(
                    null,
                    false,
                    t.message
                        ?: "Local toolchain setup failed. Fix the install, or set Prefer remote builder to true under Console → Variables.",
                )
            }
        }

        val java = st.java
        if (java == null || !st.sdkReady) {
            val msg = when {
                !st.sdkReady ->
                    "SDK data is missing. Tap Build again after the download finishes."
                !ToolchainExec.companionInstalled(app) ->
                    "Install AndVibe Build Tools when prompted, then tap Build again."
                !ToolchainExec.companionHealthy(app) ->
                    "AndVibe Build Tools needs a rebuild. Tap Build again to reinstall."
                else ->
                    "Local Java runtime not found. Install AndVibe Build Tools."
            }
            val hint = "$msg To use a remote builder, set Prefer remote builder to true under Console → Variables."
            sink(hint)
            return Triple(null, false, hint)
        }

        CompanionInstaller.linkExecutables(app, st.layout, sink)
        DebugLog.step("build", "mode=local java=${java.source}")
        sink("Gradle project. Building on this phone.")
        val nativeDir = ToolchainExec.companionNativeDir(app)
        val aapt2 = nativeDir?.let { File(it, com.example.andvibe.core.ToolchainPins.AAPT2_LIB) }
        // LD_LIBRARY_PATH must be the companion native dir (not jdk/bin).
        val libPath = nativeDir?.absolutePath ?: java.javaBin.parentFile?.absolutePath
        val javaExeHint = File(java.javaHome, "bin/java").absolutePath
        // Daemon forks must hit libjavaw.so (injects sun.jnu.encoding). Never point
        // ANDVIBE_JAVA_REAL at bare libjavabin.so or Gradle strips encoding and DNS fails.
        val javaWrap = nativeDir?.let { File(it, com.example.andvibe.core.ToolchainPins.JAVA_WRAP_LIB) }
            ?.takeIf { it.isFile }
            ?: java.javaBin
        val preload = ToolchainExec.jreHomePreload(app)
            ?: error(
                "libjrehome.so missing from the AndVibe install (needed for on-device Java). " +
                    "Reinstall AndVibe, or use a remote builder."
            )
        val prev = System.getenv("LD_PRELOAD").orEmpty()
        val jspawn = nativeDir?.let { File(it, "libjspawnhelper.so") }
        val extra = linkedMapOf(
            // Symlink path for JRE discovery (/proc/self/exe).
            "ANDVIBE_JAVA_EXE" to javaExeHint,
            // Encoding wrapper — posix_spawn rewrite target for …/jdk/bin/java.
            "ANDVIBE_JAVA_REAL" to javaWrap.absolutePath,
            "LD_PRELOAD" to
                if (prev.isBlank()) preload.absolutePath else preload.absolutePath + ":" + prev,
            "ANDVIBE_JAVAW_LOG" to File(app.filesDir, "javaw.log").absolutePath,
        )
        if (jspawn != null && jspawn.isFile) {
            extra["ANDVIBE_JSPAWNHELPER_REAL"] = jspawn.absolutePath
        }
        return try {
            val result = LocalGradleEngine.build(
                root = root,
                config = LocalGradleEngine.Config(
                    javaBin = java.javaBin,
                    javaHome = java.javaHome,
                    layout = st.layout,
                    libraryPath = libPath,
                    aapt2Override = aapt2,
                    extraEnv = extra,
                ),
                log = sink,
                cancelled = { Thread.currentThread().isInterrupted },
            )
            val dest = ApkLibrary.place(app, result.apk.name)
            result.apk.copyTo(dest, overwrite = false)
            Triple(dest, false, "")
        } catch (t: Throwable) {
            DebugLog.step("build", "local fail: ${t.message}")
            sink("local build failed: ${t.message ?: t.javaClass.simpleName}")
            throw t
        }
    }

    private fun remoteBuild(
        root: File,
        buildUrl: String,
        buildToken: String,
        sink: (String) -> Unit,
        requireRemote: Boolean,
    ): File {
        if (buildUrl.isBlank() || buildToken.isBlank()) {
            val summary = "Set the remote builder URL and token on Console → Variables."
            sink(summary)
            error(summary)
        }
        DebugLog.step("build", "mode=remote")
        sink("Sending ${root.name} to the remote builder.")
        return CloudBuild.build(app, root, buildUrl, buildToken, sink).also {
            if (!requireRemote) Unit
        }
    }

    private fun agentOrTabSummary(mode: Mode, root: File, apk: File): String {
        return if (mode == Mode.GRADLE_ONLY || mode == Mode.REMOTE_ONLY) {
            "Agent build: ${apk.name}"
        } else {
            "${root.name}: ${apk.name}"
        }
    }

    private fun tail(text: String, max: Int): String {
        return if (text.length <= max) text else "…\n" + text.takeLast(max)
    }
}
