package com.example.andvibe.features.understand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State-transition tests that don't need Android Context or TaskRunner.
 */
class UnderstandFeatureTest {
    @Test
    fun stopRequestUpdatesStoppingShape() {
        val before = UnderstandFeature.State(
            running = true,
            stopping = false,
            statusNote = "Asking model",
        )
        val after = before.copy(stopping = true, statusNote = "Stopping after this step")
        assertTrue(after.running)
        assertTrue(after.stopping)
        assertEquals("Stopping after this step", after.statusNote)
    }

    @Test
    fun cancelClearsRunningAndStopping() {
        val during = UnderstandFeature.State(running = true, stopping = true)
        val idle = during.copy(running = false, stopping = false)
        assertFalse(idle.running)
        assertFalse(idle.stopping)
    }

    @Test
    fun runClearsMarkdownAndSource() {
        val before = UnderstandFeature.State(
            markdown = "# old",
            showSource = true,
            statusNote = "Loaded UNDERSTAND.md",
        )
        val after = before.copy(
            markdown = "",
            showSource = false,
            statusNote = "Starting",
            running = true,
            stopping = false,
        )
        assertEquals("", after.markdown)
        assertFalse(after.showSource)
        assertTrue(after.running)
    }

    @Test
    fun toggleSourceRequiresMarkdown() {
        val empty = UnderstandFeature.State(markdown = "", showSource = false)
        assertFalse(empty.showSource)
        val withDoc = empty.copy(markdown = "# doc", showSource = true)
        assertTrue(withDoc.showSource)
        assertEquals("# doc", withDoc.markdown)
    }

    @Test
    fun projectChangeClearsDocKeepsRepoOptional() {
        val before = UnderstandFeature.State(
            markdown = "# x",
            statusNote = "Saved UNDERSTAND.md",
            showSource = true,
            repo = null,
        )
        val after = before.copy(
            markdown = "",
            statusNote = "",
            showSource = false,
        )
        assertEquals("", after.markdown)
        assertFalse(after.showSource)
    }
}
