package com.example.andvibe.editor

import android.content.Context
import android.graphics.Typeface
import com.example.andvibe.DebugLog
import com.example.andvibe.R
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.io.ByteArrayInputStream
import org.eclipse.tm4e.core.registry.IThemeSource

/**
 * Sora Editor glue: one-time TextMate registry setup (grammars from assets/textmate, theme from
 * the app's color tokens) and applying [EditorSettings] to a [CodeEditor].
 */
object CodeEditorSupport {
    @Volatile
    private var ready = false

    val isReady: Boolean get() = ready

    /** Loads grammars and the theme. ~1.5 MB of JSON: call off the main thread. Idempotent. */
    @Synchronized
    fun init(context: Context) {
        if (ready) return
        val app = context.applicationContext
        try {
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(app.assets))
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
            val json = EditorTheme.json(
                EditorTheme.Tokens(
                    tape = app.getColor(R.color.tape),
                    panel = app.getColor(R.color.panel),
                    raised = app.getColor(R.color.raised),
                    line = app.getColor(R.color.line),
                    ink = app.getColor(R.color.ink),
                    muted = app.getColor(R.color.muted),
                    accent = app.getColor(R.color.accent),
                    quote = app.getColor(R.color.quote),
                )
            )
            val source = IThemeSource.fromInputStream(
                ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)),
                "${EditorTheme.NAME}.json",
                Charsets.UTF_8,
            )
            ThemeRegistry.getInstance().loadTheme(ThemeModel(source, EditorTheme.NAME).apply { isDark = true }, true)
            ready = true
            DebugLog.step("editor", "textmate ready")
        } catch (t: Throwable) {
            DebugLog.step("editor", "textmate init failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** One-time per editor view: theme, font, and fixed behavior. */
    fun setup(editor: CodeEditor, context: Context) {
        editor.typefaceText = Typeface.MONOSPACE
        editor.typefaceLineNumber = Typeface.MONOSPACE
        editor.isHighlightCurrentLine = true
        editor.props.stickyScroll = true
        editor.props.formatPastedText = false
        applyColors(editor, context)
    }

    private fun applyColors(editor: CodeEditor, context: Context) {
        val scheme = if (ready) {
            runCatching { TextMateColorScheme.create(ThemeRegistry.getInstance()) }.getOrNull()
        } else {
            null
        } ?: EditorColorScheme()
        val c = { id: Int -> context.getColor(id) }
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, c(R.color.tape))
        scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, c(R.color.tape))
        scheme.setColor(EditorColorScheme.LINE_DIVIDER, c(R.color.line))
        scheme.setColor(EditorColorScheme.SCROLL_BAR_THUMB, c(R.color.line))
        scheme.setColor(EditorColorScheme.SELECTION_HANDLE, c(R.color.accent))
        if (!ready) {
            scheme.setColor(EditorColorScheme.TEXT_NORMAL, c(R.color.ink))
            scheme.setColor(EditorColorScheme.LINE_NUMBER, c(R.color.muted))
            scheme.setColor(EditorColorScheme.CURRENT_LINE, c(R.color.panel))
        }
        editor.colorScheme = scheme
    }

    /** Applies [settings] and picks the language for [fileName]. Safe to call repeatedly. */
    fun apply(editor: CodeEditor, context: Context, settings: EditorSettings, fileName: String?) {
        editor.setTextSize(settings.fontSize.toFloat())
        editor.tabWidth = settings.tabWidth
        editor.setWordwrap(settings.wordWrap)
        editor.isLineNumberEnabled = settings.lineNumbers
        editor.setPinLineNumber(settings.lineNumbers && !settings.wordWrap)
        editor.props.symbolPairAutoCompletion = settings.autoClose
        editor.props.autoIndent = settings.autoIndent
        if (ready && editor.colorScheme !is TextMateColorScheme) applyColors(editor, context)
        editor.setEditorLanguage(language(settings, fileName))
    }

    private fun language(settings: EditorSettings, fileName: String?): Language {
        val scope = fileName?.let { EditorLanguages.scopeFor(it) }
        if (!settings.highlight || scope == null || !ready) return PlainLanguage(settings)
        return try {
            TextMateLanguage.create(scope, settings.suggestions).apply {
                tabSize = settings.tabWidth
                useTab(!settings.insertSpaces)
                isAutoCompleteEnabled = settings.suggestions
            }
        } catch (t: Throwable) {
            DebugLog.step("editor", "no grammar for $scope: ${t.message}")
            PlainLanguage(settings)
        }
    }

    /** No highlighting, but still honors tabs vs spaces. */
    private class PlainLanguage(private val settings: EditorSettings) : EmptyLanguage() {
        override fun useTab(): Boolean = !settings.insertSpaces
    }
}
