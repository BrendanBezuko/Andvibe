package com.example.andvibe.agent

import java.io.File

/** Progress stream from AgentRuntime (DESIGN.md §3.7). Replaces onStep + direct shell posts. */
sealed interface AgentEvent {
    data class Step(val text: String) : AgentEvent
    data class ToolCall(val name: String, val summary: String) : AgentEvent
    data class FilesChanged(val paths: List<String>) : AgentEvent
    data class Usage(val model: String, val input: Long, val output: Long) : AgentEvent
    data class Done(val result: String, val writtenPaths: List<String>, val stopped: Boolean) : AgentEvent
    data class Failed(val message: String, val writtenPaths: List<String>) : AgentEvent
}

data class AgentResult(
    val text: String,
    val changed: List<File>,
    val steps: Int,
    val stopped: Boolean,
)
