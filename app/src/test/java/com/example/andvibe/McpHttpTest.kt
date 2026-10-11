package com.example.andvibe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class McpHttpTest {
    private fun stream(raw: String) = ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8))

    private fun post(body: String, accept: String = "application/json") = McpHttp.Request(
        "POST", "/mcp", mapOf("accept" to accept), body,
    )

    @Test
    fun readParsesRequestLineHeadersAndBody() {
        val body = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        val req = McpHttp.read(
            stream("POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body")
        )!!
        assertEquals("POST", req.method)
        assertEquals("/mcp", req.path)
        assertEquals("application/json", req.headers["content-type"])
        assertEquals(body, req.body)
    }

    @Test
    fun readHandlesMultiByteBodyByByteLength() {
        val body = """{"x":"héllo ✓"}"""
        val len = body.toByteArray(Charsets.UTF_8).size
        val req = McpHttp.read(stream("POST /mcp HTTP/1.1\r\nContent-Length: $len\r\n\r\n$body"))!!
        assertEquals(body, req.body)
    }

    @Test
    fun readReturnsNullOnEmptyStream() {
        assertNull(McpHttp.read(stream("")))
    }

    @Test
    fun readRejectsOversizedBody() {
        try {
            McpHttp.read(stream("POST /mcp HTTP/1.1\r\nContent-Length: 2000000\r\n\r\n"))
            fail("expected body too large")
        } catch (e: IllegalStateException) {
            assertEquals("body too large", e.message)
        }
    }

    @Test
    fun readRejectsOversizedHeader() {
        try {
            McpHttp.read(stream("GET /mcp HTTP/1.1\r\nX: " + "a".repeat(70_000)))
            fail("expected header too large")
        } catch (e: IllegalStateException) {
            assertEquals("header too large", e.message)
        }
    }

    @Test
    fun routeUnknownPathIs404() {
        val res = McpHttp.route(McpHttp.Request("POST", "/other", emptyMap(), "")) { fail(); null }
        assertEquals(404, res.code)
    }

    @Test
    fun routeNonPostIs405AndQueryStringIsIgnored() {
        val res = McpHttp.route(McpHttp.Request("GET", "/mcp?x=1", emptyMap(), "")) { fail(); null }
        assertEquals(405, res.code)
    }

    @Test
    fun routeNotificationIs202WithEmptyBody() {
        val res = McpHttp.route(post("{}")) { null }
        assertEquals(202, res.code)
        assertEquals("", res.body)
        assertEquals("andvibe", res.headers["Mcp-Session-Id"])
    }

    @Test
    fun routeWrapsReplyInSseWhenAccepted() {
        val res = McpHttp.route(post("{}", accept = "application/json, text/event-stream")) { "R" }
        assertEquals(200, res.code)
        assertEquals("text/event-stream", res.type)
        assertEquals("event: message\ndata: R\n\n", res.body)
        assertEquals("no-cache", res.headers["Cache-Control"])
    }

    @Test
    fun routeReturnsPlainJsonOtherwise() {
        val res = McpHttp.route(post("in")) { "out:$it" }
        assertEquals("application/json", res.type)
        assertEquals("out:in", res.body)
    }

    @Test
    fun encodeWritesStatusHeadersAndByteLength() {
        val text = McpHttp.encode(McpHttp.Response(200, "application/json", "✓", mapOf("A" to "b")))
            .toString(Charsets.UTF_8)
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Length: 3\r\n"))
        assertTrue(text.contains("Connection: close\r\n"))
        assertTrue(text.endsWith("A: b\r\n\r\n✓"))
    }
}
