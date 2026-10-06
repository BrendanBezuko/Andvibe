package com.example.andvibe.features.board

import com.example.andvibe.WorkspaceStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardFeatureTest {
    @Test
    fun defaultStateIsIdle() {
        val state = BoardFeature.State()
        assertTrue(state.cards.isEmpty())
        assertFalse(state.agentBusy)
    }

    @Test
    fun instructionForBugIncludesLeadAndCommitGuard() {
        val card = WorkspaceStore.Card(
            id = "1",
            column = WorkspaceStore.Column.BUG.id,
            title = "Crash on open",
            body = "NullPointerException",
        )
        val instruction = BoardFeature.instructionFor(card)
        assertTrue(instruction.startsWith("Fix this bug from the Board."))
        assertTrue(instruction.contains("Crash on open"))
        assertTrue(instruction.contains("NullPointerException"))
        assertTrue(instruction.contains("Do not commit."))
    }
}
