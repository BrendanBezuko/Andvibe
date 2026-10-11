package com.example.andvibe.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorSettingsTest {
    @Test
    fun roundTripsThroughMap() {
        val s = EditorSettings(fontSize = 18, tabWidth = 2, insertSpaces = false, wordWrap = true, keyBar = false, suggestions = true)
        assertEquals(s, EditorSettings.fromMap(s.toMap()))
    }

    @Test
    fun emptyMapGivesDefaults() {
        assertEquals(EditorSettings(), EditorSettings.fromMap(emptyMap<String, Any>()))
    }

    @Test
    fun clampsNumbersAndIgnoresWrongTypes() {
        val s = EditorSettings.fromMap(
            mapOf(
                EditorSettings.FONT_SIZE to 99,
                EditorSettings.TAB_WIDTH to 0,
                EditorSettings.WORD_WRAP to "yes",
                EditorSettings.LINE_NUMBERS to false,
            )
        )
        assertEquals(EditorSettings.FONT_RANGE.last, s.fontSize)
        assertEquals(EditorSettings.TAB_RANGE.first, s.tabWidth)
        assertEquals(false, s.wordWrap)
        assertEquals(false, s.lineNumbers)
    }
}
