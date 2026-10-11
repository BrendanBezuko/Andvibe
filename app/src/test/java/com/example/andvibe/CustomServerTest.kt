package com.example.andvibe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomServerTest {
    @Test
    fun jsonRoundTripPreservesStyle() {
        val server = CustomServer.new(
            name = "proxy",
            key = "k",
            model = "claude-code",
            base = "http://127.0.0.1:8787",
            style = ApiStyle.ANTHROPIC,
        )
        val parsed = CustomServer.listFromJson(CustomServer.listToJson(listOf(server)))
        assertEquals(1, parsed.size)
        assertEquals(server.id, parsed[0].id)
        assertEquals(ApiStyle.ANTHROPIC, parsed[0].style)
        assertEquals("claude-code", parsed[0].model)
    }

    @Test
    fun apiStyleFromProvider() {
        assertEquals(ApiStyle.ANTHROPIC, ApiStyle.from(Provider.ANTHROPIC))
        assertEquals(ApiStyle.OPENAI, ApiStyle.from(Provider.CUSTOM))
        assertEquals(ApiStyle.ANTHROPIC, ApiStyle.fromId("anthropic"))
        assertTrue(ApiStyle.fromId("nope") == ApiStyle.OPENAI)
    }
}
