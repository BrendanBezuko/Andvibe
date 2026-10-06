package com.example.andvibe.features.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFeatureTest {
    @Test
    fun defaultStateHasNoBlocker() {
        val state = WorkspaceFeature.State()
        assertTrue(state.workspaces.isEmpty())
        assertEquals("", state.currentId)
        assertNull(state.switchBlockedBy)
    }

    @Test
    fun blockedStateCarriesLabel() {
        val state = WorkspaceFeature.State(switchBlockedBy = "Agent working")
        assertEquals("Agent working", state.switchBlockedBy)
    }
}
