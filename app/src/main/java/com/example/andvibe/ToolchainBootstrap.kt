package com.example.andvibe

import android.content.Context
import android.os.StatFs
import com.example.andvibe.core.Sha256
import com.example.andvibe.core.ToolchainLayout
import com.example.andvibe.core.ToolchainPins
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Installs the local toolchain:
 * 1) Google SDK platform + build-tools (data) into app storage
 * 2) Termux OpenJDK + aapt/aapt2 natives via an installable companion APK
 */
class ToolchainBootstrap(private val app: Context) {
    fun layout(): ToolchainLayout =
        ToolchainLayout(File(app.filesDir, "toolchain/v${ToolchainPins.VERSION}"))

    fun status(): Status {
        val layout = layout()
        if (ToolchainExec.companionInstalled(app)) {
            CompanionInstaller.linkExecutables(app, layout) { }
        }
        val java = ToolchainExec.resolveJava(app, layout)
        return Status(
            sdkReady = layout.sdkReady(),
            companionReady = ToolchainExec.companionInstalled(app),
            java = java,
            layout = layout,
            freeBytes = freeBytes(app.filesDir),
        )
    }

    data class Status(
        val sdkReady: Boolean,
        val companionReady: Boolean,
        val java: ToolchainExec.JavaRuntime?,
        val layout: ToolchainLayout,
        val freeBytes: Long,
    ) {
        val ready: Boolean get() = sdkReady && java != null && companionReady
    }

    fun ensure(log: (String) -> Unit, promptCompanionInstall: Boolean = true): Status {
        var st = status()
        if (st.freeBytes < ToolchainPins.MIN_FREE_BYTES && !st.sdkReady) {
            error(
                "need about ${ToolchainPins.MIN_FREE_BYTES / (1024 * 1024)} MB free " +
                    "for the local Android SDK (have ${st.freeBytes / (1024 * 1024)} MB)"
            )
        }
        if (!st.sdkReady) {
            log("Downloading Android SDK ${ToolchainPins.COMPILE_SDK} + build-tools ${ToolchainPins.BUILD_TOOLS}…")
            installSdkData(st.layout, log)
            st = status()
        }
        if (!st.companionReady && promptCompanionInstall) {
            log("Fetching OpenJDK 17 + aapt/aapt2 from Termux (Bionic builds)…")
            CompanionInstaller.install(app, st.layout, log)
            st = status()
        } else if (st.companionReady) {
            CompanionInstaller.linkExecutables(app, st.layout, log)
            st = status()
        }
        return st
    }

    private fun installSdkData(layout: ToolchainLayout, log: (String) -> Unit) {
        layout.root.mkdirs()
        layout.sdkHome.mkdirs()

        val platformZip = File(app.cacheDir, ToolchainPins.platform().name)
        val toolsZip = File(app.cacheDir, ToolchainPins.buildTools().name)
        try {
            downloadArtifact(ToolchainPins.platform(), log)
            log("Extracting platform…")
            val platformTmp = File(layout.root, "_platform_tmp")
            platformTmp.deleteRecursively()
            unzip(platformZip, platformTmp, log)
            val platformSrc = findDir(platformTmp, "android-${ToolchainPins.COMPILE_SDK}")
                ?: findDir(platformTmp, "android.jar")?.parentFile
                ?: error("platform zip missing android-${ToolchainPins.COMPILE_SDK}")
            val platformDest = layout.platform
            platformDest.parentFile?.mkdirs()
            platformDest.deleteRecursively()
            if (!platformSrc.renameTo(platformDest)) {
                platformSrc.copyRecursively(platformDest, overwrite = true)
                platformSrc.deleteRecursively()
            }
            platformTmp.deleteRecursively()

            downloadArtifact(ToolchainPins.buildTools(), log)
            log("Extracting build-tools…")
            val toolsTmp = File(layout.root, "_buildtools_tmp")
            toolsTmp.deleteRecursively()
            unzip(toolsZip, toolsTmp, log)
            val toolsSrc = toolsTmp.listFiles()?.singleOrNull { it.isDirectory }
                ?: error("build-tools zip has no top-level folder")
            val toolsDest = layout.buildTools
            toolsDest.parentFile?.mkdirs()
            toolsDest.deleteRecursively()
            if (!toolsSrc.renameTo(toolsDest)) {
                toolsSrc.copyRecursively(toolsDest, overwrite = true)
                toolsSrc.deleteRecursively()
            }
            toolsTmp.deleteRecursively()

            if (!File(layout.platform, "android.jar").isFile) {
                error("SDK install missing platforms/android-${ToolchainPins.COMPILE_SDK}/android.jar")
            }
            File(layout.buildTools, "lib").mkdirs()
            File(layout.sdkHome, "licenses").mkdirs()
            File(layout.sdkHome, "licenses/android-sdk-license").writeText(
                "24333f8a63b6825ea9c5514f83c2829b004d1fee\n"
            )
            layout.markReady()
            log("SDK ready at ${layout.sdkHome.absolutePath}")
        } finally {
            platformZip.delete()
            toolsZip.delete()
        }
    }

    private fun findDir(root: File, marker: String): File? {
        if (root.name == marker && root.isDirectory) return root
        root.walkTopDown().forEach { f ->
            if (f.isDirectory && f.name == marker) return f
            if (f.isFile && f.name == marker) return f.parentFile
        }
        return null
    }

    private fun downloadArtifact(artifact: ToolchainPins.Artifact, log: (String) -> Unit): File {
        if (artifact.url.isBlank()) error("toolchain URL is not configured for ${artifact.name}")
        val dest = File(app.cacheDir, artifact.name)
        download(artifact.url, dest, artifact.sha256, log)
        return dest
    }

    private fun download(url: String, dest: File, sha256: String, log: (String) -> Unit) {
        DebugLog.step("toolchain", "GET $url")
        log("GET ${url.substringAfterLast('/')}")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 600_000
            setRequestProperty("User-Agent", "AndVibe")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                error("could not download ${dest.name} (HTTP $code) from $url")
            }
            dest.outputStream().use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    var lastLog = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        if (total - lastLog > 5L * 1024 * 1024) {
                            log("… ${total / (1024 * 1024)} MB")
                            lastLog = total
                        }
                    }
                }
            }
            if (!Sha256.matches(dest, sha256)) {
                dest.delete()
                error("toolchain checksum mismatch for ${dest.name}")
            }
            log("downloaded ${dest.length() / 1024} KB")
        } finally {
            conn.disconnect()
        }
    }

    private fun unzip(zip: File, dest: File, log: (String) -> Unit) {
        dest.mkdirs()
        var count = 0
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name.trimStart('/')
                if (name.isBlank() || name.contains("..")) {
                    zis.closeEntry()
                    continue
                }
                val out = File(dest, name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                    count++
                }
                zis.closeEntry()
            }
        }
        log("extracted $count files")
    }

    private fun freeBytes(dir: File): Long {
        return try {
            val stat = StatFs(dir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            Long.MAX_VALUE / 4
        }
    }
}
