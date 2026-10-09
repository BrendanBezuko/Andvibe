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
        // Termux OpenJDK bakes user.home / java.io.tmpdir to /data/data/com.termux/...
        val gradleHome = File(config.layout.root, "gradle-home").also { it.mkdirs() }
        val tmpDir = File(config.layout.root, "tmp").also { it.mkdirs() }
        env["HOME"] = gradleHome.absolutePath
        env["GRADLE_USER_HOME"] = gradleHome.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        env["TMP"] = tmpDir.absolutePath
        env["TEMP"] = tmpDir.absolutePath
        // Android has no locale data Gradle/Groovy expect for Charset.defaultCharset().
        env["LANG"] = "C.UTF-8"
        env["LC_ALL"] = "C.UTF-8"
        val libPath = config.libraryPath ?: config.javaBin.parentFile?.absolutePath
        if (!libPath.isNullOrBlank()) {
            val existing = env["LD_LIBRARY_PATH"].orEmpty()
            env["LD_LIBRARY_PATH"] =
                if (existing.isBlank()) libPath else libPath + File.pathSeparator + existing
        }

        // The Gradle wrapper always forks a single-use daemon. That JVM only sees
        // org.gradle.jvmargs (+ a tiny immutable prop set) — not our client -D flags
        // or JAVA_TOOL_OPTIONS. sun.jnu.encoding is NOT in Gradle's immutable list,
        // so it must be spelled out in jvmargs or DNS dies with
        // "platform encoding not initialized" (Termux OpenJDK).
        val daemonJvmArgs = daemonJvmArgs(gradleHome, tmpDir)
        writeGradleUserProps(gradleHome, config.javaHome, daemonJvmArgs)
        env["JAVA_TOOL_OPTIONS"] = listOf(
            "-Djava.io.tmpdir=${tmpDir.absolutePath}",
            "-Duser.home=${gradleHome.absolutePath}",
            "-Dfile.encoding=UTF-8",
            "-Dsun.jnu.encoding=UTF-8",
            "-Duser.language=en",
            "-Duser.country=US",
            "-Dorg.gradle.native=false",
        ).joinToString(" ")

        log("Local Gradle ${config.task} (toolchain v${ToolchainPins.VERSION})")
        log("java=${config.javaBin.absolutePath}")
        log("ANDROID_HOME=${config.layout.sdkHome.absolutePath}")
        log("GRADLE_USER_HOME=${gradleHome.absolutePath}")

        config.aapt2Override?.takeIf { it.isFile }?.let { aapt2 ->
            ensureGradleProp(root, "android.aapt2FromMavenOverride", aapt2.absolutePath)
        }
        // Avoid Kotlin compiler daemon (forks jdk/bin/java → W^X). Prefer in-process.
        ensureGradleProp(root, "kotlin.compiler.execution.strategy", "in-process")
        // AGP jlink transform needs a W^X-safe jlink; skip when we rewrite via LD_PRELOAD.
        ensureGradleProp(root, "android.experimental.disableJdkImageTransform", "true")
        // Replace project org.gradle.jvmargs so -Xmx4608m etc. cannot drop our -D flags.
        val restoreJvmArgs = replaceProjectJvmArgs(root, daemonJvmArgs, log)
        try {
            // Direct wrapper-main invocation: java binary must be executable (companion .so).
            val jvmArgs = mutableListOf(
                config.javaBin.absolutePath,
                "-Dorg.gradle.appname=gradlew",
                "-Djava.home=${config.javaHome.absolutePath}",
                "-Duser.home=${gradleHome.absolutePath}",
                "-Dgradle.user.home=${gradleHome.absolutePath}",
                "-Djava.io.tmpdir=${tmpDir.absolutePath}",
                "-Dfile.encoding=UTF-8",
                "-Dsun.jnu.encoding=UTF-8",
                "-Duser.language=en",
                "-Duser.country=US",
                "-Dorg.gradle.native=false",
                "-Dorg.gradle.daemon=false",
                "-Xmx1536m",
                "-XX:MaxMetaspaceSize=384m",
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
            val pb = ProcessBuilder(jvmArgs)
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
            var aapt2Noise = 0
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    while (true) {
                        if (cancelled() || Thread.currentThread().isInterrupted) {
                            proc.destroyForcibly()
                            error("build cancelled")
                        }
                        val line = reader.readLine() ?: break
                        val clean = stripAnsi(line)
                        if (clean.isBlank()) continue
                        // aapt2 floods stderr with harmless "No package ID 7f" lines while
                        // linking; mirroring each to the UI causes ANRs on device.
                        if (isAapt2PackageIdNoise(clean)) {
                            aapt2Noise++
                            continue
                        }
                        log(clean.take(400))
                        synchronized(output) {
                            output.append(clean).append('\n')
                            if (output.length > 200_000) output.delete(0, output.length - 120_000)
                        }
                    }
                }
                if (aapt2Noise > 0) {
                    log("aapt2: suppressed $aapt2Noise package-ID noise lines")
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
        } finally {
            restoreJvmArgs()
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

    /** JVM args the forked Gradle daemon must see (tmpdir + Termux encoding). */
    private fun daemonJvmArgs(gradleHome: File, tmpDir: File): String =
        listOf(
            "-Xmx1536m",
            "-XX:MaxMetaspaceSize=384m",
            "-Djava.io.tmpdir=${tmpDir.absolutePath}",
            "-Duser.home=${gradleHome.absolutePath}",
            "-Dfile.encoding=UTF-8",
            "-Dsun.jnu.encoding=UTF-8",
            "-Duser.language=en",
            "-Duser.country=US",
            "-Dorg.gradle.native=false",
        ).joinToString(" ")

    /**
     * Replace project `org.gradle.jvmargs` for the duration of the build so the
     * forked daemon keeps AndVibe's tmpdir/encoding flags.
     */
    private fun replaceProjectJvmArgs(
        root: File,
        daemonJvmArgs: String,
        log: (String) -> Unit,
    ): () -> Unit {
        val props = File(root, "gradle.properties")
        val original = try {
            if (props.isFile) props.readText() else null
        } catch (_: Exception) {
            return {}
        }
        val rewritten = (original ?: "").lineSequence()
            .filterNot {
                val t = it.trimStart()
                t.startsWith("org.gradle.jvmargs") ||
                    t.startsWith("# andvibe-disabled: org.gradle.jvmargs")
            }
            .toMutableList()
        rewritten.add("org.gradle.jvmargs=$daemonJvmArgs")
        props.writeText(rewritten.joinToString("\n") + "\n")
        log("Daemon JVM args pinned for on-device build (encoding + tmpdir)")
        return {
            try {
                if (original == null) props.delete() else props.writeText(original)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Shared Gradle user-home props. org.gradle.jvmargs is required: the wrapper
     * forks a daemon that only inherits these args (not client JAVA_TOOL_OPTIONS).
     */
    private fun writeGradleUserProps(gradleHome: File, javaHome: File, daemonJvmArgs: String) {
        File(gradleHome, "gradle.properties").writeText(
            """
            org.gradle.daemon=false
            org.gradle.parallel=false
            org.gradle.vfs.watch=false
            org.gradle.native=false
            org.gradle.workers.max=2
            org.gradle.jvmargs=$daemonJvmArgs
            systemProp.file.encoding=UTF-8
            systemProp.sun.jnu.encoding=UTF-8
            org.gradle.java.installations.auto-detect=false
            org.gradle.java.installations.auto-download=false
            org.gradle.java.installations.paths=${javaHome.absolutePath}
            """.trimIndent() + "\n"
        )
    }

    private fun stripAnsi(line: String): String =
        line.replace(Regex("\u001B\\[[0-?]*[ -/]*[@-~]"), "")

    /** Termux/companion aapt2 spam while resolving framework refs — not a build failure. */
    private fun isAapt2PackageIdNoise(line: String): Boolean =
        line.contains("No package ID 7f found for resource ID")
}
