package com.example.andvibe.features.board

import com.example.andvibe.WorkspaceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardFeatureTest {
    @Test
    fun defaultStateIsIdle() {
        val state = BoardFeature.State()
        assertTrue(state.cards.isEmpty())
        assertFalse(state.agentBusy)
        assertFalse(state.listening)
        assertFalse(state.drafting)
        assertTrue(state.requirements.isEmpty())
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

    @Test
    fun instructionLeadByColumn() {
        fun lead(column: String) = BoardFeature.instructionFor(
            WorkspaceStore.Card("x", column, "Title", ""),
        ).lineSequence().first()

        assertEquals(
            "Implement this feature from the Board.",
            lead(WorkspaceStore.Column.IDEA.id),
        )
        assertEquals(
            "Implement this solution from the Board.",
            lead(WorkspaceStore.Column.SOLUTION.id),
        )
        assertEquals(
            "Revisit this completed Board item.",
            lead(WorkspaceStore.Column.COMPLETED.id),
        )
    }

    @Test
    fun instructionOmitsEmptyBody() {
        val text = BoardFeature.instructionFor(
            WorkspaceStore.Card("1", WorkspaceStore.Column.IDEA.id, "Only title", "  "),
        )
        assertTrue(text.contains("Only title"))
        assertFalse(text.contains("\n\n\n\n"))
        assertTrue(text.endsWith("Do not commit."))
    }
}
