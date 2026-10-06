package com.example.andvibe.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicModelTest {
    @Test
    fun stepParsesTextAndToolUseFromCannedTranscript() {
        val requests = mutableListOf<String>()
        val http = HttpPost { _, _, body ->
            requests.add(body)
            JSONObject()
                .put(
                    "content",
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", "I'll list the root."))
                        .put(
                            JSONObject()
                                .put("type", "tool_use")
                                .put("id", "toolu_1")
                                .put("name", "list_dir")
                                .put("input", JSONObject().put("path", "."))
                        )
                )
                .put(
                    "usage",
                    JSONObject().put("input_tokens", 10).put("output_tokens", 5)
                )
                .toString()
        }
        val model = AnthropicModel("https://api.anthropic.com", "key", "claude-test", http)
        val schema = JSONObject().put("type", "object").put("properties", JSONObject())
        model.start(
            "system",
            listOf(ToolSpec("list_dir", "list", schema)),
            "list the repo",
        )
        val turn = model.step()
        assertEquals("I'll list the root.", turn.text)
        assertEquals(1, turn.calls.size)
        assertEquals("list_dir", turn.calls[0].name)
        assertEquals(".", turn.calls[0].args.optString("path"))
        assertEquals(10L, turn.input)
        assertEquals(5L, turn.output)
        assertTrue(requests.single().contains("claude-test"))
    }

    @Test
    fun addResultsAppendsToolResultBlocks() {
        val bodies = mutableListOf<String>()
        var calls = 0
        val http = HttpPost { _, _, body ->
            bodies.add(body)
            calls++
            if (calls == 1) {
                JSONObject()
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "tool_use")
                                .put("id", "t1")
                                .put("name", "list_dir")
                                .put("input", JSONObject())
                        )
                    )
                    .toString()
            } else {
                JSONObject()
                    .put(
                        "content",
                        JSONArray().put(JSONObject().put("type", "text").put("text", "done"))
                    )
                    .toString()
            }
        }
        val model = AnthropicModel("https://api.anthropic.com", "k", "m", http)
        model.start("s", listOf(ToolSpec("list_dir", "d", JSONObject().put("type", "object"))), "u")
        val first = model.step()
        model.addResults(listOf(ToolResult(first.calls[0], "(empty)", false)))
        val second = model.step()
        assertEquals("done", second.text)
        assertTrue(bodies[1].contains("tool_result"))
        assertTrue(bodies[1].contains("(empty)"))
    }
}
