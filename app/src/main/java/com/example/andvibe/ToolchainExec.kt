package com.example.andvibe

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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

    fun companionJava(context: Context): JavaRuntime? {
        val dir = companionNativeDir(context) ?: return null
        val javaBin = File(dir, ToolchainPins.JAVA_LIB)
        if (!javaBin.isFile) return null
        // Prefer the staged Termux JDK tree (modules/conf); fall back to a stub home.
        val staged = File(context.filesDir, "toolchain/v${ToolchainPins.VERSION}/jdk")
        val home = if (File(staged, "lib/modules").isFile || File(staged, "lib").isDirectory) {
            staged
        } else {
            File(context.filesDir, "toolchain/v${ToolchainPins.VERSION}/jdk-stub").also { it.mkdirs() }
        }
        return JavaRuntime(javaBin, home, "companion:${ToolchainPins.COMPANION_PACKAGE}")
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
