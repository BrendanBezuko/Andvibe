package com.example.andvibe

import com.example.andvibe.agent.HttpPost
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Drives AiClient's public calls through the swapped-in HTTP seam. */
class AiClientHttpTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Sent(val url: String, val headers: Map<String, String>, val body: JSONObject)

    private val sent = mutableListOf<Sent>()

    /** Each call pops the next responder; a responder may throw to simulate an HTTP error. */
    private fun fake(vararg replies: (Sent) -> String) {
        val queue = ArrayDeque(replies.toList())
        AiClient.http = HttpPost { url, headers, body ->
            val s = Sent(url, headers, JSONObject(body))
            sent.add(s)
            queue.removeFirst()(s)
        }
    }

    private fun chatReply(text: String) =
        """{"choices":[{"message":{"content":${JSONObject.quote(text)}}}],"usage":{"prompt_tokens":3,"completion_tokens":2}}"""

    @After
    fun restore() {
        AiClient.http = AiClient.realHttp
        AiClient.multipart = AiClient.realMultipart
    }

    @Test
    fun completeSendsOpenAiChatRequest() {
        fake({ chatReply("  hi there  ") })
        val out = AiClient.complete("sys", "hello", Provider.OPENAI, "k1", "gpt-x", "")
        assertEquals("hi there", out)
        val req = sent.single()
        assertEquals("https://api.openai.com/v1/chat/completions", req.url)
        assertEquals("Bearer k1", req.headers["Authorization"])
        assertEquals("gpt-x", req.body.getString("model"))
        assertEquals(8192, req.body.getInt("max_tokens"))
        val messages = req.body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("sys", messages.getJSONObject(0).getString("content"))
        assertEquals("hello", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun completeRetriesWithMaxCompletionTokens() {
        fake(
            { error("HTTP 400: Unsupported parameter: 'max_tokens'") },
            { chatReply("ok") },
        )
        assertEquals("ok", AiClient.complete("s", "u", Provider.OPENAI, "k", "o9", ""))
        assertEquals(2, sent.size)
        assertTrue(sent[1].body.has("max_completion_tokens"))
        assertTrue(!sent[1].body.has("max_tokens"))
    }

    @Test
    fun completeFallsBackToResponsesApiOn404() {
        fake(
            { error("HTTP 404: not found") },
            { """{"output_text":"from responses"}""" },
        )
        val out = AiClient.complete("s", "u", Provider.CUSTOM, "k", "m", "https://my.host/v1/")
        assertEquals("from responses", out)
        assertEquals("https://my.host/v1/responses", sent[1].url)
        assertEquals("s", sent[1].body.getString("instructions"))
    }

    @Test
    fun completeUsesAnthropicMessagesForAnthropicStyle() {
        fake({ """{"content":[{"type":"text","text":"claude says"}],"usage":{"input_tokens":1,"output_tokens":1}}""" })
        val out = AiClient.complete("sys", "u", Provider.CUSTOM, "ak", "m", "https://proxy.example", apiStyle = ApiStyle.ANTHROPIC)
        assertEquals("claude says", out)
        val req = sent.single()
        assertEquals("https://proxy.example/v1/messages", req.url)
        assertEquals("ak", req.headers["x-api-key"])
        assertEquals("sys", req.body.getString("system"))
    }

    @Test
    fun completeSurfacesRefusalAsError() {
        fake({ """{"choices":[{"message":{"content":null,"refusal":"I can't help with that"}}]}""" })
        try {
            AiClient.complete("s", "u", Provider.OPENAI, "k", "m", "")
            fail("expected refusal")
        } catch (e: IllegalStateException) {
            assertEquals("I can't help with that", e.message)
        }
    }

    @Test
    fun researchAddsOpenRouterWebPluginOnChatFallback() {
        fake(
            { error("HTTP 400: responses not supported") },
            { chatReply("found it") },
        )
        val out = AiClient.research("s", "q", Provider.OPENROUTER, "k", "m", "")
        assertEquals("found it", out)
        assertEquals("https://openrouter.ai/api/v1/responses", sent[0].url)
        assertEquals("web", sent[1].body.getJSONArray("plugins").getJSONObject(0).getString("id"))
    }

    @Test
    fun transcribePostsMultipartToTranscriptionsUrl() {
        val audio = tmp.newFile("a.m4a").apply { writeBytes(ByteArray(64) { 1 }) }
        var seenUrl = ""
        var seenBody = ""
        AiClient.multipart = AiClient.MultipartPost { url, _, boundary, body ->
            seenUrl = url
            seenBody = String(body, Charsets.ISO_8859_1)
            assertTrue(seenBody.contains("--$boundary"))
            """{"text":" add a card "}"""
        }
        val text = AiClient.transcribe(audio, Provider.OPENAI, "k", "")
        assertEquals("add a card", text)
        assertEquals("https://api.openai.com/v1/audio/transcriptions", seenUrl)
        assertTrue(seenBody.contains("whisper-1"))
    }

    @Test
    fun requireConfigRejectsBadInput() {
        fun msg(block: () -> Unit) = try {
            block(); ""
        } catch (e: IllegalStateException) {
            e.message.orEmpty()
        }
        assertEquals("add an API key in Settings", msg { AiClient.requireConfig(Provider.OPENAI, "", "", "m") })
        assertEquals("set a model name", msg { AiClient.requireConfig(Provider.OPENAI, "k", "", "") })
        assertEquals("model name has a space", msg { AiClient.requireConfig(Provider.OPENAI, "k", "", "a b") })
        assertEquals("set a base URL", msg { AiClient.requireConfig(Provider.CUSTOM, "k", "", "m") })
        assertEquals("base URL must start with https://", msg { AiClient.requireConfig(Provider.CUSTOM, "k", "ftp://x", "m") })
        assertEquals("https://api.x.ai/v1", AiClient.requireConfig(Provider.GROK, "k", "", null))
    }

    @Test
    fun httpErrorPrefersProviderMessage() {
        assertEquals("HTTP 401: bad key", AiClient.httpError(401, """{"error":{"message":"bad key"}}"""))
        assertEquals("HTTP 429: slow down", AiClient.httpError(429, """{"message":"slow down"}"""))
        assertEquals("HTTP 502: <html>", AiClient.httpError(502, "<html>"))
    }
}
