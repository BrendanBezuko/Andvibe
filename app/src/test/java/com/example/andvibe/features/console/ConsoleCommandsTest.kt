package com.example.andvibe.features.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleCommandsTest {
    @Test
    fun tokenizeSingleAndDoubleQuotes() {
        assertEquals(
            listOf("git", "commit", "-m", "hello world"),
            ConsoleCommands.tokenize("""git commit -m "hello world""""),
        )
        assertEquals(
            listOf("echo", "a b"),
            ConsoleCommands.tokenize("echo 'a b'"),
        )
        assertEquals(
            listOf("cmd", "first", "second third"),
            ConsoleCommands.tokenize("""cmd first "second third""""),
        )
    }

    @Test
    fun tokenizeMixedWhitespaceAndEmpty() {
        assertTrue(ConsoleCommands.tokenize("").isEmpty())
        assertTrue(ConsoleCommands.tokenize("   ").isEmpty())
        assertEquals(listOf("a", "b", "c"), ConsoleCommands.tokenize("  a   b\tc  "))
    }

    @Test
    fun tokenizeUnclosedQuoteKeepsRemainder() {
        assertEquals(
            listOf("say", "hello there"),
            ConsoleCommands.tokenize("say \"hello there"),
        )
        assertEquals(
            listOf("say", "still open"),
            ConsoleCommands.tokenize("say 'still open"),
        )
    }
}
