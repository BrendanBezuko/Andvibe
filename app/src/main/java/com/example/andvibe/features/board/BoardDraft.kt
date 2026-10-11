package com.example.andvibe.features.board

import com.example.andvibe.CardStatus
import com.example.andvibe.CardType
import com.example.andvibe.WorkspaceStore
import org.json.JSONObject

/** Pure parse/apply helpers for Board voice → LLM drafts. */
object BoardDraft {
    data class CardDraft(val type: CardType, val status: CardStatus, val title: String, val body: String)

    /** Move existing card [ref] (a short ref from [refs], e.g. "c3") to [status]. */
    data class Move(val ref: String, val status: CardStatus)

    data class Result(
        val requirementsMd: String,
        val cards: List<CardDraft>,
        val moves: List<Move> = emptyList(),
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
                val (type, status) = typeAndStatus(item)
                val body = item.optString("body").trim()
                cards.add(CardDraft(type, status, title.take(200), body.take(4000)))
            }
        }
        val moves = mutableListOf<Move>()
        val moveArr = json.optJSONArray("moves")
        if (moveArr != null) {
            for (i in 0 until moveArr.length()) {
                val item = moveArr.optJSONObject(i) ?: continue
                val ref = item.optString("ref").ifBlank { item.optString("id") }.trim().lowercase()
                val status = CardStatus.parse(item.optString("status").ifBlank { item.optString("to") })
                if (ref.isEmpty() || status == null) continue
                moves.add(Move(ref, status))
            }
        }
        return Result(requirements, cards, moves)
    }

    /** Reads type/status; falls back to the legacy single `column` key (custom prompts may still use it). */
    private fun typeAndStatus(item: JSONObject): Pair<CardType, CardStatus> {
        if (item.has("type") || item.has("status")) {
            return CardType.from(item.optString("type")) to CardStatus.from(item.optString("status"))
        }
        val column = item.optString("column").trim().lowercase()
        if (column == "completed" || column == "done") return CardType.FEATURE to CardStatus.DONE
        return CardType.from(column) to CardStatus.TODO
    }

    fun extractJson(raw: String): String {
        var text = raw.trim().removePrefix("﻿")
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

    /** Drafts that aren't duplicates by title. New work is never created already Done. */
    fun newCards(drafts: List<CardDraft>, existing: Set<String>): List<CardDraft> {
        val seen = existing.toMutableSet()
        val out = mutableListOf<CardDraft>()
        for (draft in drafts) {
            val key = draft.title.trim().lowercase()
            if (key.isEmpty() || key in seen) continue
            if (draft.status == CardStatus.DONE) continue
            seen.add(key)
            out.add(draft)
        }
        return out
    }

    /** Short refs ("c1", "c2", …) for [cards] in order, so the model never handles UUIDs. */
    fun refs(cards: List<WorkspaceStore.Card>): Map<String, WorkspaceStore.Card> =
        cards.withIndex().associate { (i, card) -> "c${i + 1}" to card }

    /** One prompt line per card: `c3 [bug · in_progress] Title`. */
    fun boardLines(refs: Map<String, WorkspaceStore.Card>): String =
        refs.entries.joinToString("\n") { (ref, card) ->
            "$ref [${card.type.id} · ${card.status.id}] ${card.title}"
        }.ifBlank { "(none)" }

    /** Resolves [moves] to (card id → status), dropping unknown refs, no-ops, and repeats (last wins). */
    fun resolveMoves(moves: List<Move>, refs: Map<String, WorkspaceStore.Card>): Map<String, CardStatus> {
        val out = LinkedHashMap<String, CardStatus>()
        for (move in moves) {
            val card = refs[move.ref] ?: continue
            out[card.id] = move.status
        }
        return out.filter { (id, status) -> refs.values.first { it.id == id }.status != status }
    }
}
