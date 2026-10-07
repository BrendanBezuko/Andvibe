package com.example.andvibe.features.board

import com.example.andvibe.WorkspaceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardDraftTest {
    @Test
    fun parseExtractsRequirementsAndCards() {
        val raw = """
            {
              "requirements_md": "# Requirements\n\n- As a user, I can record notes.",
              "cards": [
                {"column":"idea","title":"Voice capture","body":"Record spoken notes"},
                {"column":"bug","title":"Mic denied","body":""}
              ]
            }
        """.trimIndent()
        val result = BoardDraft.parse(raw)
        assertTrue(result.requirementsMd.contains("record notes"))
        assertEquals(2, result.cards.size)
        assertEquals(WorkspaceStore.Column.IDEA.id, result.cards[0].column)
        assertEquals("Voice capture", result.cards[0].title)
        assertEquals(WorkspaceStore.Column.BUG.id, result.cards[1].column)
    }

    @Test
    fun parseStripsMarkdownFence() {
        val raw = """
            ```json
            {"requirements_md":"# R","cards":[{"column":"solution","title":"Fix crash","body":"null check"}]}
            ```
        """.trimIndent()
        val result = BoardDraft.parse(raw)
        assertEquals("# R", result.requirementsMd)
        assertEquals(WorkspaceStore.Column.SOLUTION.id, result.cards.single().column)
    }

    @Test
    fun newCardsSkipsDuplicatesAndCompleted() {
        val existing = setOf("voice capture")
        val drafts = listOf(
            BoardDraft.CardDraft(WorkspaceStore.Column.IDEA.id, "Voice capture", "dup"),
            BoardDraft.CardDraft(WorkspaceStore.Column.COMPLETED.id, "Done item", ""),
            BoardDraft.CardDraft(WorkspaceStore.Column.BUG.id, "New bug", "note"),
        )
        val fresh = BoardDraft.newCards(drafts, existing)
        assertEquals(1, fresh.size)
        assertEquals("New bug", fresh.single().title)
    }

    @Test
    fun normalizeColumnAliases() {
        assertEquals(WorkspaceStore.Column.BUG.id, BoardDraft.normalizeColumn("Bugs"))
        assertEquals(WorkspaceStore.Column.SOLUTION.id, BoardDraft.normalizeColumn("fix"))
        assertEquals(WorkspaceStore.Column.IDEA.id, BoardDraft.normalizeColumn("feature"))
    }
}
