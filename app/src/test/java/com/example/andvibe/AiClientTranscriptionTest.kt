package com.example.andvibe

import org.junit.Assert.assertEquals
import org.junit.Test

class AiClientTranscriptionTest {
    @Test
    fun transcriptionUrlFromOpenAiBase() {
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            AiClient.transcriptionUrl("https://api.openai.com/v1"),
        )
    }

    @Test
    fun transcriptionUrlFromChatCompletionsBase() {
        assertEquals(
            "https://openrouter.ai/api/v1/audio/transcriptions",
            AiClient.transcriptionUrl("https://openrouter.ai/api/v1/chat/completions"),
        )
    }

    @Test
    fun transcriptionModelByProvider() {
        assertEquals("whisper-1", AiClient.transcriptionModel(Provider.OPENAI))
        assertEquals("openai/whisper-1", AiClient.transcriptionModel(Provider.OPENROUTER))
    }
}
