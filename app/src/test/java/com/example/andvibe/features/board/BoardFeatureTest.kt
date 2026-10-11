package com.example.andvibe.features.board

import com.example.andvibe.CardIssue
import com.example.andvibe.CardStatus
import com.example.andvibe.CardType
import com.example.andvibe.WorkspaceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardFeatureTest {
    private fun card(type: CardType, status: CardStatus = CardStatus.TODO, title: String = "Title", body: String = "") =
        WorkspaceStore.Card("1", type, status, title, body)

    @Test
    fun instructionForBugIncludesLeadAndCommitGuard() {
        val instruction = BoardFeature.instructionFor(card(CardType.BUG, title = "Crash on open", body = "NullPointerException"))
        assertTrue(instruction.startsWith("Fix this bug from the Board."))
        assertTrue(instruction.contains("Crash on open"))
        assertTrue(instruction.contains("NullPointerException"))
        assertTrue(instruction.contains("Do not commit."))
    }

    @Test
    fun instructionLeadByTypeAndDoneStatus() {
        fun lead(c: WorkspaceStore.Card) = BoardFeature.instructionFor(c).lineSequence().first()
        assertEquals("Implement this feature from the Board.", lead(card(CardType.FEATURE)))
        assertEquals("Implement this solution from the Board.", lead(card(CardType.SOLUTION, CardStatus.IN_PROGRESS)))
        // Done wins over type: a finished bug is revisited, not re-fixed.
        assertEquals("Revisit this completed Board item.", lead(card(CardType.BUG, CardStatus.DONE)))
    }

    @Test
    fun instructionOmitsEmptyBody() {
        val text = BoardFeature.instructionFor(card(CardType.FEATURE, title = "Only title", body = "  "))
        assertTrue(text.contains("Only title"))
        assertFalse(text.contains("\n\n\n\n"))
        assertTrue(text.endsWith("Do not commit."))
    }

    @Test
    fun instructionLinksRaisedIssue() {
        val c = card(CardType.BUG).copy(issue = CardIssue(42, "https://github.com/o/r/issues/42", "I_1"))
        assertTrue(BoardFeature.instructionFor(c).contains("GitHub issue #42: https://github.com/o/r/issues/42"))
    }
}
