package com.example.andvibe.features.vibe

import android.content.Context
import com.example.andvibe.ChatStore
import com.example.andvibe.DebugLog
import com.example.andvibe.ProjectStore
import com.example.andvibe.Provider
import com.example.andvibe.WorkspaceStore
import com.example.andvibe.agent.AgentEvent
import com.example.andvibe.agent.AgentRuntime
import com.example.andvibe.agent.AgentStart
import com.example.andvibe.features.FeatureEffects
import com.example.andvibe.tasks.Res
import com.example.andvibe.tasks.TaskRunner
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class VibeFeature(
    app: Context,
    private val agentRuntime: AgentRuntime,
    private val tasks: TaskRunner,
    private val session: com.example.andvibe.ProjectSession,
    private val onFilesChanged: () -> Unit,
    private val onGitUpdate: () -> Unit,
    private val onProjectChanged: () -> Unit,
    private val log: (String) -> Unit,
    private val gitBusy: () -> Boolean,
    private val refreshGitSnapshot: () -> Unit,
) {
    data class Creds(val provider: Provider, val key: String, val model: String, val base: String)

    data class SendContext(
        val startRoot: File?,
        val cwd: File,
        val open: File?,
        val workspaceRepos: List<File>,
    )

    data class State(
        val chat: List<Pair<String, String>> = emptyList(),
        val history: List<Pair<String, String>> = emptyList(),
        val agentSteps: List<String> = emptyList(),
        val runRepo: File? = null,
        val writtenPaths: List<String> = emptyList(),
        val busy: Boolean = false,
        val stopping: Boolean = false,
        val draftPrompt: String = "",
        /** Bumped when cwd/project changes so repo bar re-renders while idle. */
        val projectEpoch: Long = 0L,
    )

    sealed interface Effect {
        data class ReloadOpenFiles(val paths: List<String>) : Effect
        data object RefreshFileList : Effect
    }

    private val draftPrefs =
        app.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(State(draftPrompt = loadDraftFromPrefs()))
    val state: StateFlow<State> = _state.asStateFlow()

    private val effects = FeatureEffects<Effect>()
    val effectsFlow = effects.events

    fun markProjectChanged() {
        _state.update { it.copy(projectEpoch = it.projectEpoch + 1) }
    }

    fun syncBusy() {
        _state.update {
            it.copy(
                busy = tasks.holds(Res.AGENT),
                stopping = agentRuntime.isStopping(),
            )
        }
    }

    fun setDraft(text: String) {
        draftPrefs.edit().putString(KEY_DRAFT, text).apply()
        _state.update { it.copy(draftPrompt = text) }
    }

    fun consumeWrittenPaths(): List<String> {
        val paths = _state.value.writtenPaths
        if (paths.isEmpty()) return emptyList()
        _state.update { it.copy(writtenPaths = emptyList()) }
        return paths
    }

    fun historyForAi(): ArrayDeque<Pair<String, String>> = ArrayDeque(_state.value.history)

    fun applyExternalWrites(paths: List<String>) {
        if (paths.isEmpty()) return
        _state.update { it.copy(writtenPaths = paths) }
        onFilesChanged()
    }

    fun bootstrapFromStore() {
        val content = ChatStore.activeContent()
        if (content.turns.isEmpty() && content.memory.isEmpty()) return
        _state.update {
            it.copy(chat = content.turns, history = content.memory)
        }
    }

    fun syncWorkspace(): Boolean {
        if (tasks.holds(Res.AGENT)) return false
        val current = _state.value
        val loaded = ChatStore.syncWorkspace(current.chat, current.history) ?: return false
        _state.update {
            it.copy(chat = loaded.turns, history = loaded.memory)
        }
        return true
    }

    fun startNewChat(): Boolean {
        if (tasks.holds(Res.AGENT)) return false
        val current = _state.value
        val loaded = ChatStore.startNewChat(current.chat, current.history)
        _state.update {
            it.copy(chat = loaded.turns, history = loaded.memory, agentSteps = emptyList())
        }
        return true
    }

    fun openChat(id: String): Boolean {
        if (tasks.holds(Res.AGENT)) return false
        val current = _state.value
        val loaded = ChatStore.openChat(id, current.chat, current.history) ?: return false
        _state.update {
            it.copy(chat = loaded.turns, history = loaded.memory, agentSteps = emptyList())
        }
        return true
    }

    fun deleteChat(id: String): ChatStore.ChatContent? {
        if (tasks.holds(Res.AGENT)) return null
        return ChatStore.deleteChat(id)
            ?.also { loaded ->
                _state.update {
                    it.copy(chat = loaded.turns, history = loaded.memory, agentSteps = emptyList())
                }
            }
    }

    fun forgetWorkspace(workspaceId: String) {
        ChatStore.forgetWorkspace(workspaceId, tasks.holds(Res.AGENT))
        if (!tasks.holds(Res.AGENT)) {
            _state.update { it.copy(chat = emptyList(), history = emptyList()) }
        }
    }

    fun send(instruction: String, creds: Creds, ctx: SendContext) {
        if (tasks.holds(Res.AGENT)) {
            agentRuntime.current()?.cancel()
            syncBusy()
            return
        }
        val trimmed = instruction.trim()
        if (trimmed.isBlank()) return

        _state.update {
            it.copy(
                chat = it.chat + ("user" to trimmed),
                agentSteps = emptyList(),
                writtenPaths = emptyList(),
                runRepo = ctx.startRoot,
                busy = true,
                stopping = false,
            )
        }
        ChatStore.save(_state.value.chat, _state.value.history)
        setDraft("")

        var liveRoot = ctx.startRoot
        val started = agentRuntime.start(
            AgentStart(
                root = ctx.startRoot,
                repos = session.reposDir,
                cwd = ctx.cwd,
                open = ctx.open,
                task = trimmed,
                provider = creds.provider,
                key = creds.key,
                model = creds.model,
                base = creds.base,
                earlier = _state.value.history,
                workspaceRepos = ctx.workspaceRepos,
                onRootChanged = { root ->
                    liveRoot = root
                    _state.update { s -> s.copy(runRepo = root) }
                },
            ),
            onEvent = { event -> onAgentEvent(event, trimmed, ctx.startRoot) { liveRoot } },
        )
        if (started == null) {
            val reply = "an agent is already running"
            _state.update { s ->
                s.copy(
                    chat = s.chat + ("assistant" to reply),
                    busy = false,
                    stopping = false,
                    runRepo = null,
                )
            }
            ChatStore.save(_state.value.chat, _state.value.history)
        } else {
            syncBusy()
        }
    }

    private fun onAgentEvent(
        event: AgentEvent,
        instruction: String,
        startRoot: File?,
        liveRoot: () -> File?,
    ) {
        when (event) {
            is AgentEvent.Step -> {
                _state.update { s ->
                    val steps = (s.agentSteps + event.text).let { list ->
                        if (list.size > 300) list.takeLast(300) else list
                    }
                    s.copy(agentSteps = steps)
                }
                DebugLog.step("agent", event.text)
            }
            is AgentEvent.ToolCall -> Unit
            is AgentEvent.Usage -> WorkspaceStore.addUse(event.model, event.input, event.output)
            is AgentEvent.FilesChanged -> onFilesChanged()
            is AgentEvent.Done -> {
                applyRootSwitch(startRoot, liveRoot())
                finishAssistantTurn(instruction, event.result, event.writtenPaths)
                log(event.result)
                maybeRefreshGit(event.writtenPaths)
            }
            is AgentEvent.Failed -> {
                applyRootSwitch(startRoot, liveRoot())
                finishAssistantTurn(instruction, event.message, event.writtenPaths)
                log("agent error: ${event.message}")
                if (event.writtenPaths.isNotEmpty()) onGitUpdate()
            }
        }
    }

    private fun finishAssistantTurn(instruction: String, result: String, writtenPaths: List<String>) {
        recordHistory(instruction, result)
        _state.update { s ->
            s.copy(
                chat = commitAssistantReply(s.chat, s.agentSteps, result),
                agentSteps = emptyList(),
                writtenPaths = writtenPaths,
                runRepo = null,
                busy = false,
                stopping = false,
            )
        }
        ChatStore.save(_state.value.chat, _state.value.history)
        if (writtenPaths.isNotEmpty()) {
            effects.tryEmit(Effect.ReloadOpenFiles(writtenPaths))
            effects.tryEmit(Effect.RefreshFileList)
        }
        syncBusy()
    }

    private fun applyRootSwitch(startRoot: File?, liveRoot: File?) {
        val root = liveRoot ?: return
        if (startRoot?.canonicalFile == root.canonicalFile) return
        session.cwd = root
        _state.update { it.copy(runRepo = root) }
        ProjectStore.remember(session.appContext, root)
        onProjectChanged()
    }

    private fun recordHistory(instruction: String, result: String) {
        val deque = ArrayDeque(_state.value.history)
        deque.addLast("user" to instruction.take(2000))
        deque.addLast("assistant" to result.take(2000))
        while (deque.size > 8) deque.removeFirst()
        _state.update { it.copy(history = deque.toList()) }
    }

    private fun maybeRefreshGit(writtenPaths: List<String>) {
        if (writtenPaths.isEmpty() || gitBusy()) return
        refreshGitSnapshot()
        onGitUpdate()
    }

    private fun loadDraftFromPrefs(): String =
        draftPrefs.getString(KEY_DRAFT, "").orEmpty()

    companion object {
        private const val PREFS = "andvibe_ui"
        private const val KEY_DRAFT = "draft_vibe_prompt"

        /** Commits agent steps + assistant reply into chat (JVM-testable). */
        fun commitAssistantReply(
            chat: List<Pair<String, String>>,
            agentSteps: List<String>,
            result: String,
        ): List<Pair<String, String>> {
            val out = chat.toMutableList()
            val steps = agentSteps.joinToString("\n")
            if (steps.isNotBlank()) out.add("steps" to steps)
            out.add("assistant" to result)
            return out
        }
    }
}
