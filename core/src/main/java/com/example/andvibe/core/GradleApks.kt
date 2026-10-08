package com.example.andvibe.core

import java.io.File

/** Locate Gradle assemble outputs the same way the cloud worker does. */
object GradleApks {
    private val SKIP = setOf(".gradle", ".git", ".idea", "__MACOSX")

    fun find(root: File, task: String = "assembleDebug"): List<File> {
        val want = if (task.contains("release", ignoreCase = true)) "release" else "debug"
        val found = mutableListOf<File>()
        root.walkTopDown()
            .onEnter { dir -> dir.name !in SKIP || dir.name == "build" }
            .forEach { file ->
                if (!file.isFile || !file.name.endsWith(".apk")) return@forEach
                val parts = file.toPath().map { it.toString() }.toSet()
                if ("outputs" !in parts) return@forEach
                if ("androidTest" in parts) return@forEach
                found.add(file)
            }
        val preferred = found.filter { it.name.contains(want, ignoreCase = true) }
        val pool = preferred.ifEmpty { found }
        val appOnly = pool.filter { it.name == "app-debug.apk" || it.name == "app-release.apk" }
        return (if (appOnly.size == 1) appOnly else pool).sortedBy { it.absolutePath }
    }

    fun pick(root: File, task: String = "assembleDebug"): File? = find(root, task).firstOrNull()
}
