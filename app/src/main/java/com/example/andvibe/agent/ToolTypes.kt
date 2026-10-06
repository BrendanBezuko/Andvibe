package com.example.andvibe.agent

import org.json.JSONObject

class ToolSpec(val name: String, val description: String, val schema: JSONObject)

data class ToolCall(val id: String, val name: String, val args: JSONObject, val badArgs: String?)

data class ToolResult(val call: ToolCall, val output: String, val error: Boolean)

data class Turn(val text: String, val calls: List<ToolCall>, val input: Long, val output: Long)

fun interface Tool {
    fun execute(ctx: AgentContext, args: JSONObject): String
}

class RegisteredTool(
    val spec: ToolSpec,
    val plan: Boolean,
    private val body: Tool,
) {
    fun execute(ctx: AgentContext, args: JSONObject): String = body.execute(ctx, args)
}
