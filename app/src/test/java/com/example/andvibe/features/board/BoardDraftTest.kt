package com.example.andvibe.features.board

import com.example.andvibe.CardStatus
import com.example.andvibe.CardType
import com.example.andvibe.WorkspaceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardDraftTest {
    private fun card(id: String, title: String, status: CardStatus = CardStatus.TODO, type: CardType = CardType.FEATURE) =
        WorkspaceStore.Card(id, type, status, title, "")

    @Test
    fun parseExtractsRequirementsCardsAndMoves() {
        val raw = """
            {
              "requirements_md": "# Requirements\n\n- As a user, I can record notes.",
              "cards": [
                {"type":"feature","status":"todo","title":"Voice capture","body":"Record spoken notes"},
                {"type":"bug","status":"back_burner","title":"Mic denied","body":""}
              ],
              "moves": [{"ref":"C2","status":"human_testing"}, {"ref":"c9","status":"nonsense"}]
            }
        """.trimIndent()
        val result = BoardDraft.parse(raw)
        assertTrue(result.requirementsMd.contains("record notes"))
        assertEquals(BoardDraft.CardDraft(CardType.FEATURE, CardStatus.TODO, "Voice capture", "Record spoken notes"), result.cards[0])
        assertEquals(CardType.BUG, result.cards[1].type)
        assertEquals(CardStatus.BACK_BURNER, result.cards[1].status)
        assertEquals(listOf(BoardDraft.Move("c2", CardStatus.HUMAN_TESTING)), result.moves)
    }

    @Test
    fun parseAcceptsLegacyColumnKey() {
        val raw = """{"requirements_md":"# R","cards":[{"column":"solution","title":"Fix crash"},{"column":"completed","title":"Old"}]}"""
        val result = BoardDraft.parse(raw)
        assertEquals(CardType.SOLUTION to CardStatus.TODO, result.cards[0].type to result.cards[0].status)
        assertEquals(CardStatus.DONE, result.cards[1].status)
        assertTrue(result.moves.isEmpty())
    }

    @Test
    fun parseStripsMarkdownFence() {
        val raw = """
            ```json
            {"requirements_md":"# R","cards":[{"type":"solution","title":"Fix crash","body":"null check"}]}
            ```
        """.trimIndent()
        val result = BoardDraft.parse(raw)
        assertEquals("# R", result.requirementsMd)
        assertEquals(CardType.SOLUTION, result.cards.single().type)
    }

    @Test
    fun newCardsSkipsDuplicatesAndDone() {
        val drafts = listOf(
            BoardDraft.CardDraft(CardType.FEATURE, CardStatus.TODO, "Voice capture", "dup"),
            BoardDraft.CardDraft(CardType.FEATURE, CardStatus.DONE, "Done item", ""),
            BoardDraft.CardDraft(CardType.BUG, CardStatus.TODO, "New bug", "note"),
            BoardDraft.CardDraft(CardType.BUG, CardStatus.TODO, "new BUG", "same title"),
        )
        val fresh = BoardDraft.newCards(drafts, setOf("voice capture"))
        assertEquals(listOf("New bug"), fresh.map { it.title })
    }

    @Test
    fun boardLinesShowRefTypeAndStatus() {
        val refs = BoardDraft.refs(listOf(card("a", "Search"), card("b", "Login", CardStatus.IN_PROGRESS, CardType.BUG)))
        assertEquals("c1 [feature · todo] Search\nc2 [bug · in_progress] Login", BoardDraft.boardLines(refs))
        assertEquals("(none)", BoardDraft.boardLines(emptyMap()))
    }

    @Test
    fun resolveMovesDropsUnknownRefsAndNoOpsLastWins() {
        val refs = BoardDraft.refs(listOf(card("a", "Search"), card("b", "Login", CardStatus.IN_PROGRESS)))
        val moves = listOf(
            BoardDraft.Move("c1", CardStatus.IN_PROGRESS),
            BoardDraft.Move("c1", CardStatus.HUMAN_TESTING),
            BoardDraft.Move("c2", CardStatus.IN_PROGRESS), // already there
            BoardDraft.Move("c7", CardStatus.DONE), // no such card
        )
        assertEquals(mapOf("a" to CardStatus.HUMAN_TESTING), BoardDraft.resolveMoves(moves, refs))
    }
}
