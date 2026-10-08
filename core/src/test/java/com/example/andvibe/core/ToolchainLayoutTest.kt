package com.example.andvibe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ToolchainLayoutTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun sdkReadyRequiresMarkerPlatformAndBuildTools() {
        val layout = ToolchainLayout(tmp.root)
        assertFalse(layout.sdkReady())
        File(layout.platform, "android.jar").apply { parentFile.mkdirs(); writeText("jar") }
        File(layout.buildTools, "lib").mkdirs()
        assertFalse(layout.sdkReady())
        layout.markReady()
        assertTrue(layout.sdkReady())
    }

    @Test
    fun writeLocalProperties() {
        val layout = ToolchainLayout(tmp.root)
        val project = tmp.newFolder("proj")
        layout.writeLocalProperties(project)
        val text = File(project, "local.properties").readText()
        assertTrue(text.contains("sdk.dir="))
        assertEquals(layout.sdkHome.absolutePath.replace("\\", "/"), text.substringAfter("sdk.dir=").trim())
    }

    @Test
    fun normalizeAbi() {
        assertEquals("arm64-v8a", ToolchainPins.normalizeAbi("aarch64"))
        assertEquals("x86_64", ToolchainPins.normalizeAbi("amd64"))
    }
}
