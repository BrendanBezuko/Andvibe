package com.example.andvibe

import com.example.andvibe.agent.AgentEvent
import com.example.andvibe.core.GitOps
import java.io.File

/**
 * Temporary bridge from [AgentEvent] to today's AppState / UiBridge render path.
 * One file, no new UiBridge callbacks — deleted when VibeFeature lands (Phase 4 step 6).
 */
class AgentVibeAdapter(
    private val startRoot: File?,
    private val instruction: String,
    private val rememberProject: (File) -> Unit,
) {
    @Volatile var liveRoot: File? = startRoot

    fun onRootChanged(root: File) {
        liveRoot = root
        AppState.vibeRepo = root
        UiBridge.vibeUpdate()
    }

    fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Step -> {
                synchronized(AppState.agentSteps) {
                    AppState.agentSteps.add(event.text)
                    if (AppState.agentSteps.size > 300) AppState.agentSteps.removeAt(0)
                }
                DebugLog.step("agent", event.text)
                UiBridge.vibeUpdate()
            }
            is AgentEvent.ToolCall -> Unit
            is AgentEvent.Usage -> {
                WorkspaceStore.addUse(event.model, event.input, event.output)
            }
            is AgentEvent.FilesChanged -> {
                UiBridge.filesChanged()
            }
            is AgentEvent.Done -> {
                applyRootSwitch()
                recordHistory(event.result)
                if (event.writtenPaths.isNotEmpty() && !AppState.gitBusy) {
                    AppState.gitSnapshot = runCatching {
                        GitOps.snapshot(AppState.cwd, AppState.reposDir)
                    }.getOrNull()
                    UiBridge.gitUpdate()
                }
                AppState.writtenPaths = event.writtenPaths
                AppState.vibeResult = event.result
                AppState.log(event.result)
                UiBridge.vibeUpdate()
            }
            is AgentEvent.Failed -> {
                applyRootSwitch()
                if (event.writtenPaths.isNotEmpty()) UiBridge.gitUpdate()
                AppState.writtenPaths = event.writtenPaths
                AppState.vibeResult = event.message
                AppState.log("agent error: ${event.message}")
                UiBridge.vibeUpdate()
            }
        }
    }

    private fun applyRootSwitch() {
        val root = liveRoot ?: return
        if (startRoot?.canonicalFile == root.canonicalFile) return
        AppState.cwd = root
        AppState.vibeRepo = root
        rememberProject(root)
        UiBridge.projectChanged()
    }

    private fun recordHistory(result: String) {
        AppState.history.addLast("user" to instruction.take(2000))
        AppState.history.addLast("assistant" to result.take(2000))
        while (AppState.history.size > 8) AppState.history.removeFirst()
    }
}
