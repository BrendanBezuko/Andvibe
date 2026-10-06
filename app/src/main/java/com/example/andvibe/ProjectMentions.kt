package com.example.andvibe

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import java.io.File

object ProjectMentions {
    private val token = Regex("""@([A-Za-z0-9._-]+)""")

    data class AtQuery(val start: Int, val query: String)

    /** `@` typed at the cursor, with the filter text after it. */
    fun atQuery(text: CharSequence, cursor: Int): AtQuery? {
        if (cursor < 0 || cursor > text.length) return null
        var i = cursor - 1
        while (i >= 0) {
            val c = text[i]
            when {
                c == '@' -> {
                    if (i > 0 && text[i - 1].isLetterOrDigit()) return null
                    return AtQuery(i, text.substring(i + 1, cursor))
                }
                c.isLetterOrDigit() || c == '.' || c == '_' || c == '-' -> i--
                else -> return null
            }
        }
        return null
    }

    fun namesIn(text: CharSequence, known: Collection<String>): List<String> {
        if (known.isEmpty() || text.isEmpty()) return emptyList()
        val set = known.toSet()
        val found = linkedSetOf<String>()
        for (match in token.findAll(text)) {
            val name = match.groupValues[1]
            if (name in set) found.add(name)
        }
        return found.toList()
    }

    fun dirsIn(text: CharSequence, dirs: List<File>): List<File> {
        val names = namesIn(text, dirs.map { it.name })
        if (names.isEmpty()) return emptyList()
        val byName = dirs.associateBy { it.name }
        return names.mapNotNull { byName[it] }
    }

    fun filterRepos(dirs: List<File>, query: String, exclude: String? = null): List<File> {
        val q = query.lowercase()
        return dirs.filter {
            it.name != exclude && (q.isEmpty() || it.name.lowercase().contains(q))
        }
    }

    fun applySpans(
        text: Spannable,
        known: Collection<String>,
        fill: Int,
        stroke: Int,
        textColor: Int
    ) {
        text.getSpans(0, text.length, MentionSpan::class.java).forEach { text.removeSpan(it) }
        if (known.isEmpty()) return
        val set = known.toSet()
        for (match in token.findAll(text)) {
            if (match.groupValues[1] !in set) continue
            text.setSpan(
                MentionSpan(fill, stroke, textColor),
                match.range.first,
                match.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    fun insert(
        text: CharSequence,
        replaceStart: Int,
        replaceEnd: Int,
        name: String,
        known: Collection<String>,
        fill: Int,
        stroke: Int,
        textColor: Int
    ): Pair<Spannable, Int> {
        val tokenText = "@$name"
        val builder = SpannableStringBuilder(text)
        val end = replaceEnd.coerceIn(0, builder.length)
        val start = replaceStart.coerceIn(0, end)
        builder.replace(start, end, "$tokenText ")
        applySpans(builder, known, fill, stroke, textColor)
        return builder to (start + tokenText.length + 1)
    }

    fun contextBlock(root: File): String {
        return buildString {
            append("Referenced repo @").append(root.name)
            append(" (read-only context; tools still only work in the selected repo):\n")
            append("File tree (partial):\n").append(AiClient.tree(root, 80)).append('\n')
            for (name in listOf("UNDERSTAND.md", "README.md", "AGENTS.md", "CLAUDE.md")) {
                val file = File(root, name)
                if (file.isFile && file.length() < 120_000) {
                    append(name).append(":\n")
                    append(file.readText().take(4_000)).append("\n\n")
                    break
                }
            }
        }
    }
}
