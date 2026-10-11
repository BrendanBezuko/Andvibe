package com.example.andvibe.agent

import com.example.andvibe.ApiStyle
import com.example.andvibe.Provider
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelsCustomTest {
    @Test
    fun customAnthropicUsesAnthropicModel() {
        val model = AgentModels.create(
            provider = Provider.CUSTOM,
            key = "k",
            model = "claude-code",
            baseUrl = "http://127.0.0.1:8787",
            http = HttpPost { _, _, _ -> error("unused") },
            apiStyle = ApiStyle.ANTHROPIC,
        )
        assertTrue(model is AnthropicModel)
    }

    @Test
    fun customOpenAiUsesOpenAiModel() {
        val model = AgentModels.create(
            provider = Provider.CUSTOM,
            key = "k",
            model = "local",
            baseUrl = "http://127.0.0.1:11434/v1",
            http = HttpPost { _, _, _ -> error("unused") },
            apiStyle = ApiStyle.OPENAI,
        )
        assertTrue(model is OpenAiModel)
    }
}
