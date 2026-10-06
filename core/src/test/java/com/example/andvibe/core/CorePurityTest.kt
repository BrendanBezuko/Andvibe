package com.example.andvibe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** core/ must stay pure JVM: no android.* or androidx.* imports (DESIGN.md §3.1). */
class CorePurityTest {
    @Test
    fun coreHasNoAndroidImports() {
        val dir = sequenceOf(
            "src/main/java/com/example/andvibe/core", // :core module cwd
            "core/src/main/java/com/example/andvibe/core",
        ).map(::File).firstOrNull { it.isDirectory }
        assertTrue("core source dir not found from ${File(".").absolutePath}", dir != null)
        val offenders = dir!!.walkTopDown()
            .filter { it.extension in setOf("kt", "java") }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    if (line.trim().startsWith("import android")) "${file.name}:${i + 1} $line" else null
                }
            }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
