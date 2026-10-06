package com.example.andvibe.features.vibe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VibeFeatureTest {
    @Test
    fun commitAddsStepsThenAssistant() {
        val chat = listOf("user" to "fix bug")
        val steps = listOf("read file", "edit file")
        val out = VibeFeature.commitAssistantReply(chat, steps, "Fixed.")
        assertEquals(3, out.size)
        assertEquals("user", out[0].first)
        assertEquals("steps", out[1].first)
        assertTrue(out[1].second.contains("read file"))
        assertEquals("assistant" to "Fixed.", out[2])
    }

    @Test
    fun commitSkipsEmptySteps() {
        val chat = listOf("user" to "hi")
        val out = VibeFeature.commitAssistantReply(chat, emptyList(), "Hello")
        assertEquals(2, out.size)
        assertEquals("assistant" to "Hello", out[1])
    }

    @Test
    fun busyShapeWhileRunning() {
        val running = VibeFeature.State(
            chat = listOf("user" to "go"),
            agentSteps = listOf("step"),
            busy = true,
            stopping = false,
        )
        assertTrue(running.busy)
        assertFalse(running.stopping)
        assertEquals(1, running.agentSteps.size)
    }

    @Test
    fun idleAfterRunClearsSteps() {
        val during = VibeFeature.State(
            chat = listOf("user" to "go", "assistant" to "done"),
            agentSteps = emptyList(),
            busy = false,
        )
        assertFalse(during.busy)
        assertTrue(during.agentSteps.isEmpty())
    }
}
