package com.example.andvibe.core

/**
 * Pinned on-device Gradle toolchain.
 * SDK platform + build-tools download from Google's repository.
 * JDK + aapt/aapt2 natives come from Termux packages, packed into a companion APK
 * (Android 10+ W^X — executables must live in nativeLibraryDir).
 */
object ToolchainPins {
    const val VERSION = "1"
    const val COMPANION_PACKAGE = "com.example.andvibe.toolchain"
    const val JAVA_LIB = "libjavabin.so"
    const val AAPT2_LIB = "libaapt2bin.so"
    const val AAPT_LIB = "libaaptbin.so"
    const val COMPILE_SDK = "34"
    const val BUILD_TOOLS = "34.0.0"
    const val MIN_FREE_BYTES = 1500L * 1024 * 1024

    private const val GOOGLE_REPO = "https://dl.google.com/android/repository"

    data class Artifact(
        val name: String,
        val url: String,
        val sha256: String = "",
    )

    fun platform(): Artifact = Artifact(
        name = "platform-34-ext7_r03.zip",
        url = "$GOOGLE_REPO/platform-34-ext7_r03.zip",
    )

    fun buildTools(): Artifact = Artifact(
        name = "build-tools_r34-linux.zip",
        url = "$GOOGLE_REPO/build-tools_r34-linux.zip",
    )

    fun normalizeAbi(raw: String): String {
        val a = raw.lowercase()
        return when {
            a.contains("arm64") || a == "aarch64" -> "arm64-v8a"
            a.contains("armeabi") || a == "armv7l" || a == "arm" -> "armeabi-v7a"
            a.contains("x86_64") || a == "amd64" -> "x86_64"
            a.contains("x86") -> "x86"
            else -> "arm64-v8a"
        }
    }
}
