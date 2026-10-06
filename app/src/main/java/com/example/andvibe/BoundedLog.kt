package com.example.andvibe

/**
 * Append-only text log that trims itself from the front: once it grows past
 * [limit] characters it keeps only the last [keep].
 */
class BoundedLog(private val limit: Int = 120_000, private val keep: Int = 80_000) {
    init {
        require(keep in 0..limit) { "keep must be within 0..limit" }
    }

    private val buffer = StringBuilder()

    /** Appends [line] (trailing whitespace stripped) plus a newline. Returns the stripped line. */
    @Synchronized
    fun append(line: String): String {
        val text = line.trimEnd()
        buffer.append(text).append('\n')
        if (buffer.length > limit) {
            buffer.delete(0, buffer.length - keep)
        }
        return text
    }

    @Synchronized
    fun text(): String = buffer.toString()

    @Synchronized
    fun replace(content: String) {
        buffer.setLength(0)
        buffer.append(content.takeLast(keep))
    }

    @Synchronized
    fun clear() {
        buffer.setLength(0)
    }
}
