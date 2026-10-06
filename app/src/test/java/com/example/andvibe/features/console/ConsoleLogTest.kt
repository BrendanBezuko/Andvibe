package com.example.andvibe.features.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleLogTest {
    @Test
    fun appendAndClear() {
        val log = ConsoleLog()
        log.append("one")
        log.append("two")
        assertTrue(log.text().contains("one"))
        assertTrue(log.text().contains("two"))
        val rev = log.revision.value
        log.clear()
        assertEquals("", log.text().trim())
        assertTrue(log.revision.value > rev)
    }

    @Test
    fun tokenizeRespectsQuotes() {
        val parts = ConsoleCommands.tokenize("""git commit -m "hello world"""")
        assertEquals(listOf("git", "commit", "-m", "hello world"), parts)
    }
}
