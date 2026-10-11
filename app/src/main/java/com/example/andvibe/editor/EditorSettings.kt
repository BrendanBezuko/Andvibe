package com.example.andvibe.editor

import android.content.Context
import android.content.SharedPreferences

/** User-tunable code editor options (Settings → Editor). */
data class EditorSettings(
    val fontSize: Int = 14,
    val tabWidth: Int = 4,
    val insertSpaces: Boolean = true,
    val wordWrap: Boolean = false,
    val lineNumbers: Boolean = true,
    val highlight: Boolean = true,
    val autoClose: Boolean = true,
    val autoIndent: Boolean = true,
    val suggestions: Boolean = false,
    val keyBar: Boolean = true,
) {
    fun toMap(): Map<String, Any> = mapOf(
        FONT_SIZE to fontSize,
        TAB_WIDTH to tabWidth,
        INSERT_SPACES to insertSpaces,
        WORD_WRAP to wordWrap,
        LINE_NUMBERS to lineNumbers,
        HIGHLIGHT to highlight,
        AUTO_CLOSE to autoClose,
        AUTO_INDENT to autoIndent,
        SUGGESTIONS to suggestions,
        KEY_BAR to keyBar,
    )

    companion object {
        const val FONT_SIZE = "font_size"
        const val TAB_WIDTH = "tab_width"
        const val INSERT_SPACES = "insert_spaces"
        const val WORD_WRAP = "word_wrap"
        const val LINE_NUMBERS = "line_numbers"
        const val HIGHLIGHT = "highlight"
        const val AUTO_CLOSE = "auto_close"
        const val AUTO_INDENT = "auto_indent"
        const val SUGGESTIONS = "suggestions"
        const val KEY_BAR = "key_bar"

        val FONT_RANGE = 8..32
        val TAB_RANGE = 1..8

        /** Reads stored values, ignoring wrong types and clamping numbers to sane ranges. */
        fun fromMap(map: Map<String, *>): EditorSettings {
            val d = EditorSettings()
            fun int(key: String, def: Int, range: IntRange) = ((map[key] as? Number)?.toInt() ?: def).coerceIn(range)
            fun bool(key: String, def: Boolean) = map[key] as? Boolean ?: def
            return EditorSettings(
                fontSize = int(FONT_SIZE, d.fontSize, FONT_RANGE),
                tabWidth = int(TAB_WIDTH, d.tabWidth, TAB_RANGE),
                insertSpaces = bool(INSERT_SPACES, d.insertSpaces),
                wordWrap = bool(WORD_WRAP, d.wordWrap),
                lineNumbers = bool(LINE_NUMBERS, d.lineNumbers),
                highlight = bool(HIGHLIGHT, d.highlight),
                autoClose = bool(AUTO_CLOSE, d.autoClose),
                autoIndent = bool(AUTO_INDENT, d.autoIndent),
                suggestions = bool(SUGGESTIONS, d.suggestions),
                keyBar = bool(KEY_BAR, d.keyBar),
            )
        }
    }
}

/** SharedPreferences-backed [EditorSettings]; [listen] fires on any change (e.g. from Settings). */
class EditorPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("andvibe_editor", Context.MODE_PRIVATE)
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    fun load(): EditorSettings = EditorSettings.fromMap(prefs.all)

    fun save(settings: EditorSettings) {
        val edit = prefs.edit()
        for ((key, value) in settings.toMap()) {
            when (value) {
                is Int -> edit.putInt(key, value)
                is Boolean -> edit.putBoolean(key, value)
            }
        }
        edit.apply()
    }

    fun update(block: (EditorSettings) -> EditorSettings) = save(block(load()))

    fun listen(onChange: (EditorSettings) -> Unit) {
        // SharedPreferences keeps listeners weakly; hold them here.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange(load()) }
        listeners += listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }
}
