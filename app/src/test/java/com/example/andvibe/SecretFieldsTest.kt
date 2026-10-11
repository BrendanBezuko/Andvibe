package com.example.andvibe

import org.junit.Assert.assertEquals
import org.junit.Test

class SecretFieldsTest {
    @Test
    fun blankKeepsExisting() {
        assertEquals("sk-old", SecretFields.nextSecret("", "sk-old"))
        assertEquals("sk-old", SecretFields.nextSecret("   ", "sk-old"))
        assertEquals("", SecretFields.nextSecret("", ""))
    }

    @Test
    fun typedReplacesExisting() {
        assertEquals("sk-new", SecretFields.nextSecret("sk-new", "sk-old"))
        assertEquals("sk-new", SecretFields.nextSecret("  sk-new  ", "sk-old"))
    }

    @Test
    fun hintReflectsStoredState() {
        assertEquals("OpenAI API key", SecretFields.hint("OpenAI API key", hasStored = false))
        assertEquals(SecretFields.SAVED_HINT, SecretFields.hint("OpenAI API key", hasStored = true))
    }
}
