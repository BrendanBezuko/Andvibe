package com.example.andvibe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedLogTest {
    @Test
    fun appendStripsTrailingWhitespaceAndAddsNewline() {
        val log = BoundedLog()
        val returned = log.append("hello  \t")
        assertEquals("hello", returned)
        assertEquals("hello\n", log.text())
    }

    @Test
    fun trimsFromTheFrontPastLimit() {
        val log = BoundedLog(limit = 100, keep = 50)
        repeat(20) { log.append("line-%02d".format(it)) }
        val text = log.text()
        assertTrue(text.length <= 100)
        assertFalse("oldest line should be trimmed", text.contains("line-00"))
        assertTrue("newest line must survive", text.contains("line-19"))
    }

    @Test
    fun replaceKeepsOnlyTail() {
        val log = BoundedLog(limit = 100, keep = 50)
        log.replace("a".repeat(30) + "b".repeat(50))
        assertEquals("b".repeat(50), log.text())
    }

    @Test
    fun clearEmptiesBuffer() {
        val log = BoundedLog()
        log.append("something")
        log.clear()
        assertEquals("", log.text())
    }
}
