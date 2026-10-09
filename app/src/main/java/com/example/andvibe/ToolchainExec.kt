package com.example.andvibe

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.andvibe.core.ElfNeeded
import com.example.andvibe.core.ToolchainLayout
import com.example.andvibe.core.ToolchainPins
import java.io.File

/**
 * Resolves a runnable `java` binary for [LocalGradleEngine].
 * Prefer the companion tools APK (executables in nativeLibraryDir); fall back to a
 * JDK extracted under the layout (works in JVM tests / older Android).
 */
object ToolchainExec {
    data class JavaRuntime(val javaBin: File, val javaHome: File, val source: String)

    fun abi(): String {
        val abis = Build.SUPPORTED_ABIS
        if (abis != null) {
            for (a in abis) {
                val n = ToolchainPins.normalizeAbi(a)
                if (n.isNotBlank()) return n
            }
        }
        return ToolchainPins.normalizeAbi(System.getProperty("os.arch") ?: "arm64-v8a")
    }

    fun resolveJava(context: Context, layout: ToolchainLayout): JavaRuntime? {
        companionJava(context)?.let { return it }
        val bundled = layout.bundledJava() ?: return null
        if (!canProbeExec(bundled)) return null
        val home = bundled.parentFile?.parentFile ?: return null
        return JavaRuntime(bundled, home, "bundled-jdk")
    }

    fun companionInstalled(context: Context): Boolean =
        companionNativeDir(context) != null

    /**
     * True when the companion APK has a real zlib. A 0-byte `libz.so` means the
     * Build Tools package was built from broken tar-symlink extraction.
     */
    fun companionHealthy(context: Context): Boolean {
        val dir = companionNativeDir(context) ?: return false
        val javaBin = File(dir, ToolchainPins.JAVA_LIB)
        val zlib = File(dir, "libz.so")
        if (!javaBin.isFile || javaBin.length() == 0L) return false
        if (!zlib.isFile || zlib.length() <= 1_000) return false
        // Older companions shipped real libz.so but left libjli needing libz.so.1
        // (Android will not extract versioned sonames from the APK).
        val jli = File(dir, "libjli.so")
        if (jli.isFile) {
            val needed = ElfNeeded.listNeeded(jli.readBytes())
            if (needed.any { it.contains(".so.") }) return false
        }
        // Flattened filename with versioned DT_SONAME breaks aapt2 (libpng → libz).
        val soname = ElfNeeded.listSoname(zlib.readBytes())
        if (soname != null && soname.contains(".so.")) return false
        // Hyphenated / plus Termux deps were dropped by an over-strict lib name filter.
        if (!File(dir, "libandroid-spawn.so").isFile ||
            !File(dir, "libandroid-shmem.so").isFile ||
            !File(dir, "libcxx_shared.so").isFile
        ) {
            return false
        }
        // Encoding + spawn wrappers required for Gradle's forked daemon on Android.
        if (!File(dir, ToolchainPins.JAVA_WRAP_LIB).isFile) return false
        if (!File(dir, ToolchainPins.SPAWN_WRAP_LIB).isFile) return false
        return true
    }

    fun companionJava(context: Context): JavaRuntime? {
        val dir = companionNativeDir(context) ?: return null
        if (!companionHealthy(context)) return null
        // Prefer the encoding wrapper; Gradle daemons strip -Dsun.jnu.encoding.
        val wrapped = File(dir, ToolchainPins.JAVA_WRAP_LIB)
        val javaBin = if (wrapped.isFile) wrapped else File(dir, ToolchainPins.JAVA_LIB)
        if (!javaBin.isFile) return null
        val staged = File(context.filesDir, "toolchain/v${ToolchainPins.VERSION}/jdk")
        val home = if (File(staged, "lib/modules").isFile || File(staged, "lib").isDirectory) {
            staged
        } else {
            File(context.filesDir, "toolchain/v${ToolchainPins.VERSION}/jdk-stub").also { it.mkdirs() }
        }
        return JavaRuntime(javaBin, home, "companion:${ToolchainPins.COMPANION_PACKAGE}")
    }

    /** LD_PRELOAD helper that fixes OpenJDK /proc/self/exe JRE discovery. */
    fun jreHomePreload(context: Context): File? {
        val so = File(context.applicationInfo.nativeLibraryDir, "libjrehome.so")
        return so.takeIf { it.isFile }
    }

    fun companionNativeDir(context: Context): File? {
        return try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getApplicationInfo(
                    ToolchainPins.COMPANION_PACKAGE,
                    PackageManager.ApplicationInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(ToolchainPins.COMPANION_PACKAGE, 0)
            }
            val dir = File(info.nativeLibraryDir)
            if (dir.isDirectory) dir else null
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    /** Best-effort: true when the file exists; real exec is verified when Gradle starts. */
    private fun canProbeExec(bin: File): Boolean = bin.isFile && bin.canExecute()
}
