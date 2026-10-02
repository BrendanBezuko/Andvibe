package com.example.andvibe

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object DebugLog {
    private const val MAX_LINES = 2000
    private val lines = ArrayDeque<Line>()

    private class Line(val at: Long, val area: String, val message: String)

    fun step(area: String, message: String) {
        val text = message.trim()
        if (text.isEmpty()) return
        val name = area.trim().ifBlank { "app" }
        synchronized(lines) {
            lines.addLast(Line(System.currentTimeMillis(), name, text))
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        Log.i("AndVibe", "$name | $text")
    }

    fun text(area: String?, limit: Int): String {
        val cap = limit.coerceIn(1, MAX_LINES)
        val want = area?.trim().orEmpty()
        val snapshot = synchronized(lines) { lines.toList() }
        val matched = if (want.isEmpty()) {
            snapshot
        } else {
            snapshot.filter { it.area == want || it.area.startsWith("$want.") }
        }
        val shown = if (matched.size > cap) matched.takeLast(cap) else matched
        if (shown.isEmpty()) return if (want.isEmpty()) "no debug logs yet" else "no debug logs for $want"
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return shown.joinToString("\n") { line ->
            "${fmt.format(Date(line.at))} ${line.area} | ${line.message}"
        }
    }
}
