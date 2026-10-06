package com.example.andvibe

import com.example.andvibe.core.JsRunner
import com.example.andvibe.tasks.Res
import com.example.andvibe.tasks.TaskRunner
import android.content.Context
import java.io.File

/**
 * Single build path for the Build tab and the agent's cloud_build tool
 * (DESIGN.md §3.7 / Phase 3). Mutual exclusion is Res.BUILD — claimed by
 * TaskRunner.launch for the tab, or nest-claimed here when the agent calls in.
 */
class BuildService(
    private val app: Context,
    private val tasks: TaskRunner,
    private val buildLog: com.example.andvibe.features.build.BuildLog? = null,
    private val onBuildChanged: (() -> Unit)? = null,
) {
    enum class Mode {
        /** gradlew → Cloud Run; otherwise local JsRunner + ApkPackager. */
        AUTO,
        /** Agent tool: Cloud Run only; errors if no gradlew. */
        CLOUD_ONLY,
    }

    data class Outcome(
        val title: String,
        val summary: String,
        val apk: File?,
        val cloud: Boolean,
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
        var cloud = false
        try {
            cloud = File(root, "gradlew").isFile
            DebugLog.step("build", "start path=${root.absolutePath} gradlew=$cloud mode=$mode")
            when {
                mode == Mode.CLOUD_ONLY || cloud -> {
                    if (mode == Mode.CLOUD_ONLY && !cloud) {
                        error("this repo has no gradlew, so Cloud Run cannot build it")
                    }
                    if (buildUrl.isBlank() || buildToken.isBlank()) {
                        title = "Build needs setup"
                        summary = if (mode == Mode.CLOUD_ONLY) {
                            "the Cloud Run URL or token is not set. The user sets them on Console → Variables."
                        } else {
                            "Set the build URL and token on Console → Variables."
                        }
                        sink(summary)
                        if (mode == Mode.CLOUD_ONLY) error(summary)
                    } else {
                        DebugLog.step("build", "mode=cloud")
                        if (mode == Mode.AUTO) sink("Gradle project. Sending it to Cloud Run.")
                        val apk = CloudBuild.build(app, root, buildUrl, buildToken, sink)
                        built = apk
                        title = "Build ready"
                        summary = if (mode == Mode.CLOUD_ONLY) {
                            "Agent build: ${apk.name}"
                        } else {
                            "${root.name}: ${apk.name}"
                        }
                    }
                }
                else -> {
                    DebugLog.step("build", "mode=local")
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
                        if (cloud) "Tap Install."
                        else "Tap Install. The new app is named Built app."
                    )
                }
            }
        } catch (t: Throwable) {
            DebugLog.step("build", "fail ${t.javaClass.simpleName}: ${t.message}")
            val message = t.message ?: t.javaClass.simpleName
            if (mode == Mode.CLOUD_ONLY) {
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
        return Outcome(title, summary, built, cloud)
    }

    /**
     * Agent cloud_build tool entry. Nest-claims BUILD, optionally mirrors into [buildLog],
     * and returns the tool result string.
     */
    fun agentCloudBuild(
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
        note("Agent build. Sending ${root.name} to Cloud Run.")
        return try {
            val outcome = run(
                root = root,
                buildUrl = buildUrl,
                buildToken = buildToken,
                mode = Mode.CLOUD_ONLY,
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

    private fun tail(text: String, max: Int): String {
        return if (text.length <= max) text else "…\n" + text.takeLast(max)
    }
}
