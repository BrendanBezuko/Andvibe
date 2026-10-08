package com.example.andvibe

import android.content.Context
import android.content.Intent
import android.system.Os
import androidx.core.content.FileProvider
import com.example.andvibe.core.ToolchainLayout
import com.example.andvibe.core.ToolchainPins
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Download Termux OpenJDK + aapt/aapt2 (and deps), stage the JDK tree, pack
 * executables/.so into a companion APK, and prompt the user to install it.
 */
object CompanionInstaller {
    fun install(context: Context, layout: ToolchainLayout, log: (String) -> Unit): File {
        val abi = ToolchainExec.abi()
        if (abi != "arm64-v8a" && abi != "armeabi-v7a") {
            error("Local Build Tools on-device install supports arm phones only (this device reports $abi).")
        }
        val work = File(context.cacheDir, "termux-fetch")
        work.deleteRecursively()
        work.mkdirs()
        val debsDir = File(work, "debs")
        val extractRoot = File(work, "root")
        extractRoot.mkdirs()

        val index = TermuxPackages.fetchIndex(abi, log)
        val pkgs = TermuxPackages.resolveClosure(index)
        log("Resolved ${pkgs.size} Termux packages (OpenJDK 17 + aapt/aapt2 + libs)")
        val debs = TermuxPackages.downloadAll(pkgs, debsDir, log)

        log("Extracting packages…")
        for (deb in debs) {
            DebExtractor.extract(deb, extractRoot)
        }

        stageJdk(extractRoot, layout, log)
        val natives = collectNatives(extractRoot, abi, log)
        val apk = buildCompanionApk(context, abi, natives, log)
        log("Install AndVibe Build Tools when prompted, then tap Build again.")
        promptInstall(context, apk)
        return apk
    }

    /** After the companion APK is installed, point JDK/SDK tool paths at its native libs. */
    fun linkExecutables(context: Context, layout: ToolchainLayout, log: (String) -> Unit) {
        val native = ToolchainExec.companionNativeDir(context) ?: return
        val javaSo = File(native, ToolchainPins.JAVA_LIB)
        val aapt2So = File(native, ToolchainPins.AAPT2_LIB)
        val spawnSo = File(native, "libjspawnhelper.so")
        if (!javaSo.isFile) return

        val bin = File(layout.jdkHome, "bin")
        bin.mkdirs()
        symlinkReplace(File(bin, "java"), javaSo)
        if (spawnSo.isFile) {
            File(layout.jdkHome, "lib").mkdirs()
            symlinkReplace(File(layout.jdkHome, "lib/jspawnhelper"), spawnSo)
        }
        if (aapt2So.isFile) {
            layout.buildTools.mkdirs()
            symlinkReplace(File(layout.buildTools, "aapt2"), aapt2So)
            val aaptSo = File(native, ToolchainPins.AAPT_LIB)
            if (aaptSo.isFile) symlinkReplace(File(layout.buildTools, "aapt"), aaptSo)
        }
        // Force AGP to the companion aapt2 (executable native lib path).
        if (aapt2So.isFile) {
            val props = File(layout.root, "gradle-aapt2.properties")
            props.writeText("android.aapt2FromMavenOverride=${aapt2So.absolutePath}\n")
        }
        log("Linked java/aapt2 into toolchain from companion package")
    }

    private fun stageJdk(extractRoot: File, layout: ToolchainLayout, log: (String) -> Unit) {
        val jvm = File(extractRoot, "lib/jvm/java-17-openjdk")
        if (!jvm.isDirectory) error("Termux openjdk-17 did not unpack lib/jvm/java-17-openjdk")
        layout.jdkHome.deleteRecursively()
        layout.jdkHome.parentFile?.mkdirs()
        // Copy tree; executables will be symlinked to the companion APK after install.
        jvm.copyRecursively(layout.jdkHome, overwrite = true)
        log("JDK data staged at ${layout.jdkHome.absolutePath}")
    }

    private fun collectNatives(extractRoot: File, abi: String, log: (String) -> Unit): Map<String, File> {
        val out = LinkedHashMap<String, File>()
        fun putLib(name: String, file: File) {
            if (!file.isFile) return
            out[name] = file
        }

        val jvm = File(extractRoot, "lib/jvm/java-17-openjdk")
        putLib(ToolchainPins.JAVA_LIB, File(jvm, "bin/java"))
        putLib("libjspawnhelper.so", File(jvm, "lib/jspawnhelper"))
        // All JVM shared objects (including server/libjvm.so → libjvm.so).
        jvm.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            when {
                file.name.endsWith(".so") -> {
                    val name = if (file.name.startsWith("lib")) file.name else "lib${file.name}"
                    // Prefer the first occurrence; server/libjvm.so overwrites plain if later.
                    out[name] = file
                }
            }
        }
        putLib(ToolchainPins.AAPT2_LIB, File(extractRoot, "bin/aapt2"))
        putLib(ToolchainPins.AAPT_LIB, File(extractRoot, "bin/aapt"))

        // Shared libs from dependency packages under usr/lib → extractRoot/lib
        File(extractRoot, "lib").walkTopDown().maxDepth(2).forEach { file ->
            if (file.isFile && file.name.endsWith(".so")) {
                val name = if (file.name.startsWith("lib")) file.name else "lib${file.name}"
                out.putIfAbsent(name, file)
            }
        }

        if (ToolchainPins.JAVA_LIB !in out) error("missing java binary from Termux openjdk")
        if (ToolchainPins.AAPT2_LIB !in out) error("missing aapt2 binary from Termux aapt2 package")
        log("Packaging ${out.size} native files for $abi")
        return out
    }

    private fun buildCompanionApk(
        context: Context,
        abi: String,
        natives: Map<String, File>,
        log: (String) -> Unit,
    ): File {
        val template = File(context.cacheDir, "toolchain-template.apk")
        context.assets.open("toolchain-template.apk").use { input ->
            template.outputStream().use { input.copyTo(it) }
        }
        val unsigned = File(context.cacheDir, "toolchain-unsigned.apk")
        val signed = File(context.cacheDir, "andvibe-toolchain.apk")
        rewriteApkWithNatives(template, unsigned, abi, natives)
        log("Signing Build Tools APK…")
        if (signed.exists()) signed.delete()
        ApkPackager.signApk(context, unsigned, signed)
        log("Build Tools APK ready (${signed.length() / 1024} KB)")
        return signed
    }

    private fun rewriteApkWithNatives(
        template: File,
        dest: File,
        abi: String,
        natives: Map<String, File>,
    ) {
        if (dest.exists()) dest.delete()
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            ZipFile(template).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.name.startsWith("lib/")) continue
                    zos.putNextEntry(ZipEntry(entry.name))
                    zip.getInputStream(entry).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            for ((name, file) in natives) {
                val entry = ZipEntry("lib/$abi/$name")
                entry.method = ZipEntry.STORED
                val bytes = file.readBytes()
                entry.size = bytes.size.toLong()
                entry.compressedSize = bytes.size.toLong()
                entry.crc = crc32(bytes)
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    private fun crc32(bytes: ByteArray): Long {
        val crc = java.util.zip.CRC32()
        crc.update(bytes)
        return crc.value
    }

    private fun symlinkReplace(link: File, target: File) {
        link.parentFile?.mkdirs()
        if (link.exists() || link.isFile) link.delete()
        try {
            Os.symlink(target.absolutePath, link.absolutePath)
        } catch (_: Throwable) {
            // Fall back to a tiny shell trampoline; may fail W^X on some devices.
            link.writeText("#!/system/bin/sh\nexec \"${target.absolutePath}\" \"\$@\"\n")
            link.setExecutable(true, false)
        }
    }

    private fun promptInstall(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
