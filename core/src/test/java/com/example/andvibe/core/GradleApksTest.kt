package com.example.andvibe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GradleApksTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun prefersAppDebugApk() {
        val root = tmp.root
        val out = File(root, "app/build/outputs/apk/debug")
        out.mkdirs()
        File(out, "app-debug.apk").writeText("a")
        File(out, "extra-debug.apk").writeText("b")
        File(root, "app/build/outputs/apk/androidTest/debug/androidTest.apk").apply {
            parentFile.mkdirs()
            writeText("c")
        }
        val pick = GradleApks.pick(root)
        assertEquals("app-debug.apk", pick?.name)
    }

    @Test
    fun emptyWhenNoOutputs() {
        assertTrue(GradleApks.find(tmp.root).isEmpty())
    }
}
