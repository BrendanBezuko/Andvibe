package com.example.andvibe.editor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorThemeTest {
    private val tokens = EditorTheme.Tokens(
        tape = 0xFF010409.toInt(), panel = 0xFF161B22.toInt(), raised = 0xFF21262D.toInt(),
        line = 0xFF30363D.toInt(), ink = 0xFFE6EDF3.toInt(), muted = 0xFF8B949E.toInt(),
        accent = 0xFF58A6FF.toInt(), quote = 0xFFD29922.toInt(),
    )

    @Test
    fun hexIsRgbWithAlphaLast() {
        assertEquals("#58A6FF", EditorTheme.hex(0xFF58A6FF.toInt()))
        assertEquals("#58A6FF40", EditorTheme.hex(0xFF58A6FF.toInt(), alpha = 0x40))
    }

    @Test
    fun uiColorsComeFromTokens() {
        val json = JSONObject(EditorTheme.json(tokens))
        assertEquals(EditorTheme.NAME, json.getString("name"))
        val colors = json.getJSONObject("colors")
        assertEquals("#010409", colors.getString("editor.background"))
        assertEquals("#E6EDF3", colors.getString("editor.foreground"))
        assertEquals("#58A6FF", colors.getString("editorCursor.foreground"))
        assertEquals("#8B949E", colors.getString("editorLineNumber.foreground"))
    }

    @Test
    fun tokenRulesCoverCoreScopes() {
        val rules = JSONObject(EditorTheme.json(tokens)).getJSONArray("tokenColors")
        val scopes = (0 until rules.length()).flatMap { i ->
            rules.getJSONObject(i).optJSONArray("scope")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        }.toSet()
        for (core in listOf("comment", "keyword", "string", "constant.numeric", "entity.name.function", "entity.name.type")) {
            assertTrue("missing $core", core in scopes)
        }
    }
}
