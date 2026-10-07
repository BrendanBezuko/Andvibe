package com.example.andvibe.features.board

import com.example.andvibe.WorkspaceStore
import org.json.JSONObject

/** Pure parse/apply helpers for Board voice → LLM drafts. */
object BoardDraft {
    data class CardDraft(val column: String, val title: String, val body: String)

    data class Result(
        val requirementsMd: String,
        val cards: List<CardDraft>,
    )

    fun parse(raw: String): Result {
        val json = JSONObject(extractJson(raw))
        val requirements = json.optString("requirements_md").trim()
        val cards = mutableListOf<CardDraft>()
        val arr = json.optJSONArray("cards")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                if (title.isEmpty()) continue
                val column = normalizeColumn(item.optString("column"))
                val body = item.optString("body").trim()
                cards.add(CardDraft(column, title.take(200), body.take(4000)))
            }
        }
        return Result(requirements, cards)
    }

    fun normalizeColumn(raw: String): String {
        val key = raw.trim().lowercase()
        return when (key) {
            WorkspaceStore.Column.BUG.id, "bugs", "bug" -> WorkspaceStore.Column.BUG.id
            WorkspaceStore.Column.SOLUTION.id, "solutions", "solution", "fix" ->
                WorkspaceStore.Column.SOLUTION.id
            WorkspaceStore.Column.COMPLETED.id, "done", "complete" ->
                WorkspaceStore.Column.COMPLETED.id
            else -> WorkspaceStore.Column.IDEA.id
        }
    }

    fun extractJson(raw: String): String {
        var text = raw.trim().removePrefix("\uFEFF")
        if (text.startsWith("```")) {
            text = text.substringAfter('\n', text)
            if (text.trimEnd().endsWith("```")) text = text.trimEnd().dropLast(3)
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) error("model did not return JSON")
        return text.substring(start, end + 1)
    }

    fun existingTitles(cards: List<WorkspaceStore.Card>): Set<String> =
        cards.map { it.title.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    fun newCards(drafts: List<CardDraft>, existing: Set<String>): List<CardDraft> {
        val seen = existing.toMutableSet()
        val out = mutableListOf<CardDraft>()
        for (draft in drafts) {
            val key = draft.title.trim().lowercase()
            if (key.isEmpty() || key in seen) continue
            if (draft.column == WorkspaceStore.Column.COMPLETED.id) continue
            seen.add(key)
            out.add(draft)
        }
        return out
    }
}
