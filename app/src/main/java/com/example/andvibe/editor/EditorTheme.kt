package com.example.andvibe.editor

import org.json.JSONArray
import org.json.JSONObject

/**
 * The editor's TextMate theme. UI colors come from the app's color tokens (colors.xml) so the
 * editor can't drift from the theme. Syntax colors are GitHub Dark's, like FileIcons' brand table:
 * a sanctioned palette, not theme tokens (STYLE.md §6).
 */
object EditorTheme {
    const val NAME = "andvibe-dark"

    /** ARGB ints from colors.xml. */
    data class Tokens(
        val tape: Int,
        val panel: Int,
        val raised: Int,
        val line: Int,
        val ink: Int,
        val muted: Int,
        val accent: Int,
        val quote: Int,
    )

    private val syntax = listOf(
        listOf("comment", "punctuation.definition.comment", "string.comment") to "#8B949E",
        listOf("keyword", "storage", "storage.type", "storage.modifier", "keyword.operator.new") to "#FF7B72",
        listOf("string", "string.quoted", "string.template", "markup.inline.raw", "markup.fenced_code") to "#A5D6FF",
        listOf("constant", "constant.numeric", "constant.language", "support.constant", "variable.other.constant", "entity.name.constant") to "#79C0FF",
        listOf("entity.name.function", "support.function", "meta.function-call entity.name.function") to "#D2A8FF",
        listOf("entity.name.type", "entity.name.class", "support.class", "support.type", "entity.other.inherited-class", "storage.type.java", "storage.type.kotlin") to "#FFA657",
        listOf("variable.parameter", "variable.language", "entity.name.variable.parameter") to "#FFA657",
        listOf("entity.name.tag", "meta.tag.sgml", "punctuation.definition.tag") to "#7EE787",
        listOf("entity.other.attribute-name", "support.type.property-name", "meta.object-literal.key") to "#79C0FF",
        listOf("string.regexp", "constant.character.escape") to "#A5D6FF",
        listOf("markup.heading", "entity.name.section") to "#79C0FF",
        listOf("markup.bold") to "#E6EDF3",
        listOf("markup.italic") to "#E6EDF3",
        listOf("markup.underline.link", "string.other.link") to "#A5D6FF",
        listOf("invalid", "invalid.illegal") to "#FFA198",
        listOf("meta.annotation", "storage.type.annotation", "punctuation.definition.annotation") to "#D2A8FF",
    )

    fun json(t: Tokens): String {
        val colors = JSONObject()
            .put("editor.background", hex(t.tape))
            .put("editor.foreground", hex(t.ink))
            .put("editorCursor.foreground", hex(t.accent))
            .put("editor.selectionBackground", hex(t.accent, alpha = 0x40))
            .put("editor.lineHighlightBackground", hex(t.panel))
            .put("editorLineNumber.foreground", hex(t.muted))
            .put("editorLineNumber.activeForeground", hex(t.ink))
            .put("editorWhitespace.foreground", hex(t.line))
            .put("editorIndentGuide.background", hex(t.line))
            .put("editorIndentGuide.activeBackground", hex(t.muted))
            .put("editor.findMatchBackground", hex(t.quote, alpha = 0x66))
            .put("editor.wordHighlightBackground", hex(t.raised))
            .put("editor.wordHighlightStrongBackground", hex(t.raised))
            .put("editorSuggestWidget.background", hex(t.panel))
            .put("editorSuggestWidget.foreground", hex(t.ink))
            .put("editorSuggestWidget.selectedBackground", hex(t.raised))
            .put("editorSuggestWidget.highlightForeground", hex(t.accent))
        val rules = JSONArray()
            .put(JSONObject().put("settings", JSONObject().put("foreground", hex(t.ink)).put("background", hex(t.tape))))
        for ((scopes, color) in syntax) {
            val settings = JSONObject().put("foreground", color)
            if ("markup.bold" in scopes) settings.put("fontStyle", "bold")
            if ("markup.italic" in scopes || "comment" in scopes) settings.put("fontStyle", "italic")
            rules.put(JSONObject().put("scope", JSONArray(scopes)).put("settings", settings))
        }
        return JSONObject()
            .put("name", NAME)
            .put("type", "dark")
            .put("colors", colors)
            .put("tokenColors", rules)
            .toString()
    }

    /** #RRGGBB, or #RRGGBBAA when [alpha] is given (TextMate order: alpha last). */
    internal fun hex(argb: Int, alpha: Int? = null): String {
        val rgb = String.format("#%06X", argb and 0xFFFFFF)
        return if (alpha == null) rgb else rgb + String.format("%02X", alpha and 0xFF)
    }
}
