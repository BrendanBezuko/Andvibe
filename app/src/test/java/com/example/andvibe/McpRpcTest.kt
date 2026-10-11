package com.example.andvibe

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpRpcTest {
    private val calls = mutableListOf<Pair<String, JSONObject>>()
    private val rpc = McpRpc { name, args ->
        calls.add(name to args)
        when (name) {
            "boom" -> error("kaboom")
            else -> McpRpc.ok("ran $name")
        }
    }

    private fun send(json: String): JSONObject = JSONObject(rpc.dispatch(json)!!)

    @Test
    fun initializeEchoesRequestedProtocol() {
        val res = send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""")
        assertEquals(1, res.getInt("id"))
        val result = res.getJSONObject("result")
        assertEquals("2025-06-18", result.getString("protocolVersion"))
        assertEquals("andvibe", result.getJSONObject("serverInfo").getString("name"))
        assertTrue(result.getJSONObject("capabilities").has("tools"))
    }

    @Test
    fun initializeDefaultsProtocolWhenMissing() {
        val res = send("""{"jsonrpc":"2.0","id":"a","method":"initialize"}""")
        assertEquals("a", res.getString("id"))
        assertEquals(McpRpc.DEFAULT_PROTOCOL, res.getJSONObject("result").getString("protocolVersion"))
    }

    @Test
    fun toolsListNamesEveryTool() {
        val tools = send("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            .getJSONObject("result").getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertEquals(listOf("logs", "build_log", "console_log", "state", "screenshot"), names)
        val limit = tools.getJSONObject(0).getJSONObject("inputSchema")
            .getJSONObject("properties").getJSONObject("limit")
        assertEquals("integer", limit.getString("type"))
    }

    @Test
    fun toolsCallPassesNameAndArguments() {
        val res = send("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"logs","arguments":{"area":"git"}}}""")
        assertEquals("logs", calls.single().first)
        assertEquals("git", calls.single().second.getString("area"))
        val result = res.getJSONObject("result")
        assertFalse(result.getBoolean("isError"))
        assertEquals("ran logs", result.getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun toolsCallWithoutArgumentsGetsEmptyObject() {
        send("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"state"}}""")
        assertEquals(0, calls.single().second.length())
    }

    @Test
    fun throwingToolBecomesIsErrorResult() {
        val result = send("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"boom"}}""")
            .getJSONObject("result")
        assertTrue(result.getBoolean("isError"))
        assertEquals("kaboom", result.getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun unknownMethodIsMethodNotFound() {
        val err = send("""{"jsonrpc":"2.0","id":6,"method":"nope"}""").getJSONObject("error")
        assertEquals(-32601, err.getInt("code"))
    }

    @Test
    fun malformedJsonIsParseErrorWithNullId() {
        val res = send("not json")
        assertTrue(res.isNull("id"))
        assertEquals(-32700, res.getJSONObject("error").getInt("code"))
    }

    @Test
    fun notificationGetsNoReply() {
        assertNull(rpc.dispatch("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun pingReturnsEmptyResult() {
        assertEquals(0, send("""{"jsonrpc":"2.0","id":7,"method":"ping"}""").getJSONObject("result").length())
    }

    @Test
    fun screenshotNameSanitizesRequestedName() {
        assertEquals("my-shot_1", McpRpc.screenshotName(" my-shot_1.png ", "git", "t"))
        assertEquals("myshot", McpRpc.screenshotName("my shot!", "git", "t"))
        assertEquals("abc", McpRpc.screenshotName("a/b\\c.png", "git", "t"))
    }

    @Test
    fun screenshotNameFallsBackToLabelAndStamp() {
        assertEquals("screen-20261010-120000", McpRpc.screenshotName("", "screen", "20261010-120000"))
        assertEquals("vibe-x", McpRpc.screenshotName("../..", "vibe", "x"))
    }

    @Test
    fun screenshotNameIsCappedAt60Chars() {
        assertEquals(60, McpRpc.screenshotName("a".repeat(100), "l", "s").length)
    }
}
