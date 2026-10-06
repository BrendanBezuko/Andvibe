package com.example.andvibe.features.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** State and log contracts for [BuildFeature] / [BuildLog] (no Android Context). */
class BuildFeatureTest {
    @Test
    fun buildLogAppendUpdatesTextAndRevision() {
        val log = BuildLog()
        assertEquals("", log.text.value)
        assertEquals(0, log.revision.value)
        log.append("line one")
        assertTrue(log.text.value.contains("line one"))
        assertEquals(1, log.revision.value)
        log.append("line two")
        assertEquals(2, log.revision.value)
    }

    @Test
    fun buildLogClearResets() {
        val log = BuildLog()
        log.append("x")
        log.clear()
        assertEquals("", log.text.value)
        assertEquals("", log.snapshot())
    }

    @Test
    fun buildLogReplaceLoadsHistoryShape() {
        val log = BuildLog()
        log.replace("failed: compile error\n")
        assertEquals("failed: compile error\n", log.snapshot())
    }

    @Test
    fun stateTracksBusyFlagsAndApk() {
        var state = BuildFeature.State()
        assertFalse(state.building)
        assertFalse(state.revising)
        assertNull(state.lastApkPath)
        state = state.copy(
            building = true,
            logText = "Building…",
            lastApkPath = "/data/app.apk",
        )
        assertTrue(state.building)
        assertEquals("/data/app.apk", state.lastApkPath)
        state = state.copy(building = false, revising = true)
        assertTrue(state.revising)
        assertFalse(state.building)
    }

    @Test
    fun emptyLogBlocksReviseMessageContract() {
        val note = "Build first. Revise uses that log."
        val state = BuildFeature.State(logText = note)
        assertEquals(note, state.logText)
    }
}
