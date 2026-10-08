package com.example.andvibe.core

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs a project's Gradle wrapper with a pinned on-device toolchain.
 * Pure JVM — Android callers supply [Config.javaBin] from the companion tools APK
 * (nativeLibraryDir). Invokes the wrapper JAR directly so Android 10+ W^X rules
 * never need to exec a script from writable storage.
 */
object LocalGradleEngine {
    const val DEFAULT_TASK = "assembleDebug"
    const val DEFAULT_TIMEOUT_MS = 20 * 60 * 1000L

    data class Result(
        val apk: File,
        val exitCode: Int,
    )

    data class Config(
        val javaBin: File,
        val javaHome: File,
        val layout: ToolchainLayout,
        val task: String = DEFAULT_TASK,
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        val extraEnv: Map<String, String> = emptyMap(),
        /** Native lib dir for LD_LIBRARY_PATH (companion APK). */
        val libraryPath: String? = null,
        /** Absolute path to a Bionic aapt2 binary (companion libaapt2bin.so). */
        val aapt2Override: File? = null,
    )

    fun build(
        root: File,
        config: Config,
        log: (String) -> Unit,
        cancelled: () -> Boolean = { false },
    ): Result {
        val gradlew = File(root, "gradlew")
        if (!gradlew.isFile) error("no gradlew in ${root.name}")
        normalizeWrapper(gradlew)
        config.layout.writeLocalProperties(root)

        if (!config.javaBin.isFile) {
            error("java binary missing: ${config.javaBin.absolutePath}")
        }
        if (!config.layout.sdkReady()) {
            error("Android SDK toolchain is not installed. Tap Build to download it, or set a remote builder URL.")
        }

        val wrapperJar = File(root, "gradle/wrapper/gradle-wrapper.jar")
        if (!wrapperJar.isFile) {
            error("missing gradle/wrapper/gradle-wrapper.jar")
        }

        val env = LinkedHashMap<String, String>()
        env.putAll(System.getenv())
        env.putAll(config.layout.env(config.javaHome))
        env.putAll(config.extraEnv)
        env["JAVA_HOME"] = config.javaHome.absolutePath
        val libPath = config.libraryPath ?: config.javaBin.parentFile?.absolutePath
        if (!libPath.isNullOrBlank()) {
            val existing = env["LD_LIBRARY_PATH"].orEmpty()
            env["LD_LIBRARY_PATH"] =
                if (existing.isBlank()) libPath else libPath + File.pathSeparator + existing
        }

        log("Local Gradle ${config.task} (toolchain v${ToolchainPins.VERSION})")
        log("java=${config.javaBin.absolutePath}")
        log("ANDROID_HOME=${config.layout.sdkHome.absolutePath}")

        config.aapt2Override?.takeIf { it.isFile }?.let { aapt2 ->
            ensureGradleProp(root, "android.aapt2FromMavenOverride", aapt2.absolutePath)
        }

        // Direct wrapper-main invocation: java binary must be executable (companion .so).
        val jvmArgs = mutableListOf(
            config.javaBin.absolutePath,
            "-Dorg.gradle.appname=gradlew",
            "-Djava.home=${config.javaHome.absolutePath}",
        )
        config.aapt2Override?.takeIf { it.isFile }?.let {
            jvmArgs.add("-Dandroid.aapt2FromMavenOverride=${it.absolutePath}")
        }
        jvmArgs.addAll(
            listOf(
                "-classpath",
                wrapperJar.absolutePath,
                "org.gradle.wrapper.GradleWrapperMain",
                "--no-daemon",
                "--no-watch-fs",
                config.task,
            )
        )
        val command = jvmArgs
        val pb = ProcessBuilder(command)
            .directory(root)
            .redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(env)

        val active = AtomicReference<Process>(null)
        val proc = try {
            pb.start().also { active.set(it) }
        } catch (t: Throwable) {
            val msg = t.message.orEmpty()
            if (msg.contains("Permission denied", ignoreCase = true) || msg.contains("error=13")) {
                error(
                    "cannot execute the build tools on this Android version. " +
                        "Install the AndVibe Build Tools package, or use a remote builder URL."
                )
            }
            throw t
        }

        val output = StringBuilder()
        try {
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                while (true) {
                    if (cancelled() || Thread.currentThread().isInterrupted) {
                        proc.destroyForcibly()
                        error("build cancelled")
                    }
                    val line = reader.readLine() ?: break
                    val clean = stripAnsi(line)
                    if (clean.isNotBlank()) log(clean.take(400))
                    synchronized(output) {
                        output.append(clean).append('\n')
                        if (output.length > 200_000) output.delete(0, output.length - 120_000)
                    }
                }
            }
            if (!awaitExit(proc, config.timeoutMs)) {
                proc.destroyForcibly()
                error("local build timed out after ${config.timeoutMs / 1000}s")
            }
            val code = proc.exitValue()
            if (code != 0) {
                val tail = synchronized(output) { output.toString() }.takeLast(8_000).trim()
                error(if (tail.isBlank()) "gradle failed (exit $code)" else "gradle failed (exit $code)\n$tail")
            }
            val apk = GradleApks.pick(root, config.task)
                ?: error("gradle finished but no APK was produced under outputs/")
            log("APK ${apk.name} (${apk.length() / 1024} KB)")
            return Result(apk, code)
        } finally {
            active.get()?.destroyForcibly()
        }
    }

    private fun awaitExit(proc: Process, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                proc.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(200)
            }
        }
        return false
    }

    private fun normalizeWrapper(gradlew: File) {
        gradlew.setExecutable(true, false)
        val text = try {
            gradlew.readText()
        } catch (_: Exception) {
            return
        }
        if (text.contains("\r\n")) {
            gradlew.writeText(text.replace("\r\n", "\n"))
            gradlew.setExecutable(true, false)
        }
    }

    private fun ensureGradleProp(root: File, key: String, value: String) {
        val props = File(root, "gradle.properties")
        val line = "$key=$value"
        val text = if (props.isFile) props.readText() else ""
        val lines = text.lines().filter { it.isNotEmpty() || text.isEmpty() }.toMutableList()
        val idx = lines.indexOfFirst { it.startsWith("$key=") }
        if (idx >= 0) lines[idx] = line else lines.add(line)
        props.writeText(lines.joinToString("\n") + "\n")
    }

    private fun stripAnsi(line: String): String =
        line.replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "")
}
