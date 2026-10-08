package com.example.andvibe.core

import java.io.File

/**
 * On-disk layout for the Android SDK portion of the local toolchain.
 * [root] is typically `context.filesDir/toolchain/v1`.
 */
class ToolchainLayout(val root: File) {
    val sdkHome: File get() = File(root, "android-sdk")
    val platform: File get() = File(sdkHome, "platforms/android-${ToolchainPins.COMPILE_SDK}")
    val buildTools: File get() = File(sdkHome, "build-tools/${ToolchainPins.BUILD_TOOLS}")
    val marker: File get() = File(root, "ready.marker")
    val jdkHome: File get() = File(root, "jdk")

    fun sdkReady(): Boolean =
        marker.isFile &&
            File(platform, "android.jar").isFile &&
            File(buildTools, "lib").isDirectory

    /** JDK under filesDir — usable on desktop JVM tests; on Android 10+ often not executable. */
    fun bundledJava(): File? {
        val candidates = listOf(
            File(jdkHome, "bin/java"),
            File(jdkHome, "jre/bin/java"),
        )
        return candidates.firstOrNull { it.isFile }
    }

    fun env(javaHome: File?): Map<String, String> {
        val out = linkedMapOf(
            "ANDROID_HOME" to sdkHome.absolutePath,
            "ANDROID_SDK_ROOT" to sdkHome.absolutePath,
        )
        if (javaHome != null) {
            out["JAVA_HOME"] = javaHome.absolutePath
        }
        return out
    }

    fun writeLocalProperties(projectRoot: File) {
        File(projectRoot, "local.properties").writeText(
            "sdk.dir=${sdkHome.absolutePath.replace("\\", "/")}\n"
        )
    }

    fun markReady() {
        root.mkdirs()
        marker.writeText("version=${ToolchainPins.VERSION}\nsdk=${sdkHome.absolutePath}\n")
    }
}
