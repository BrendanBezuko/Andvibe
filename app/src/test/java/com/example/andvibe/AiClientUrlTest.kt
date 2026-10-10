package com.example.andvibe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiClientUrlTest {
    @Test
    fun chatUrlAppendsOrKeepsCompletionsPath() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            AiClient.chatUrl("https://api.openai.com/v1"),
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            AiClient.chatUrl("https://api.openai.com/v1/chat/completions/"),
        )
    }

    @Test
    fun responsesUrlFromBaseOrChatCompletions() {
        assertEquals(
            "https://api.openai.com/v1/responses",
            AiClient.responsesUrl("https://api.openai.com/v1"),
        )
        assertEquals(
            "https://api.openai.com/v1/responses",
            AiClient.responsesUrl("https://api.openai.com/v1/chat/completions"),
        )
        assertEquals(
            "https://api.openai.com/v1/responses",
            AiClient.responsesUrl("https://api.openai.com/v1/responses"),
        )
    }

    @Test
    fun anthropicUrlBuildsMessagesPath() {
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            AiClient.anthropicUrl("https://api.anthropic.com"),
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            AiClient.anthropicUrl("https://api.anthropic.com/v1"),
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            AiClient.anthropicUrl("https://api.anthropic.com/v1/messages"),
        )
    }

    @Test
    fun geminiUrlBuildsGenerateContent() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2:generateContent",
            AiClient.geminiUrl(
                "https://generativelanguage.googleapis.com/v1beta",
                "gemini-2",
            ),
        )
        val full =
            "https://generativelanguage.googleapis.com/v1beta/models/x:generateContent"
        assertEquals(full, AiClient.geminiUrl(full, "ignored"))
    }

    @Test
    fun guessTokensAndReadUse() {
        assertEquals(0L, AiClient.guessTokens(0))
        assertEquals(1L, AiClient.guessTokens(1))
        assertEquals(4L, AiClient.guessTokens(16))

        assertEquals(10L to 5L, AiClient.readUse("""{"usage":{"input_tokens":10,"output_tokens":5}}"""))
        assertEquals(3L to 2L, AiClient.readUse("""{"usage":{"prompt_tokens":3,"completion_tokens":2}}"""))
        assertEquals(
            7L to 1L,
            AiClient.readUse("""{"usageMetadata":{"promptTokenCount":7,"candidatesTokenCount":1}}"""),
        )
        assertEquals(0L to 0L, AiClient.readUse("not-json"))
    }

    @Test
    fun extraHeadersOnlyForOpenRouter() {
        assertTrue(AiClient.extraHeaders(Provider.OPENAI).isEmpty())
        assertEquals(mapOf("X-Title" to "AndVibe"), AiClient.extraHeaders(Provider.OPENROUTER))
    }
}
