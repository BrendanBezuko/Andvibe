package com.example.andvibe.features.understand

import com.example.andvibe.DebugLog
import com.example.andvibe.Provider
import com.example.andvibe.Tab
import com.example.andvibe.Understand
import com.example.andvibe.features.FeatureEffects
import com.example.andvibe.tasks.AppDispatchers
import com.example.andvibe.tasks.Res
import com.example.andvibe.tasks.TaskRunner
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class UnderstandFeature(
    private val tasks: TaskRunner,
    private val dispatchers: AppDispatchers,
    private val log: (String) -> Unit,
) {
    data class Creds(val provider: Provider, val key: String, val model: String, val base: String)

    data class State(
        val repo: File? = null,
        val markdown: String = "",
        val statusNote: String = "",
        val running: Boolean = false,
        val stopping: Boolean = false,
        val showSource: Boolean = false,
    )

    sealed interface Effect {
        data object PickRepo : Effect
        data class Saved(val root: File) : Effect
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val effects = FeatureEffects<Effect>()
    val effectsFlow = effects.events

    @Volatile
    private var activeTask: TaskRunner.Task? = null
    private val stopFlag = AtomicBoolean(false)

    fun toggleSource() {
        if (_state.value.markdown.isBlank()) return
        _state.update { it.copy(showSource = !it.showSource) }
    }

    fun setRepo(repo: File?) {
        _state.update { it.copy(repo = repo) }
    }

    fun clearForProjectChange() {
        _state.update {
            it.copy(
                markdown = "",
                statusNote = "",
                showSource = false,
            )
        }
    }

    fun loadSaved(root: File) {
        if (tasks.holds(Res.UNDERSTAND) || _state.value.markdown.isNotBlank()) return
        val saved = Understand.loadSaved(root) ?: return
        _state.update {
            it.copy(
                markdown = saved,
                statusNote = "Loaded UNDERSTAND.md",
            )
        }
    }

    fun syncBusy() {
        val busy = tasks.holds(Res.UNDERSTAND)
        _state.update {
            it.copy(running = busy, stopping = if (!busy) false else it.stopping)
        }
    }

    fun run(root: File, focus: String, creds: Creds) {
        if (tasks.holds(Res.UNDERSTAND)) {
            stop()
            return
        }
        if (creds.key.isBlank() || creds.model.isBlank()) {
            _state.update { it.copy(statusNote = "Add an API key in Settings") }
            log("understand: add an API key in Settings")
            return
        }
        stopFlag.set(false)
        _state.update {
            it.copy(
                repo = root,
                markdown = "",
                statusNote = "Starting",
                running = true,
                stopping = false,
                showSource = false,
            )
        }
        val task = tasks.launch(
            "Understanding ${root.name}",
            Tab.UNDERSTAND,
            setOf(Res.UNDERSTAND),
            dispatchers.repo,
            track = true,
        ) {
            var title = "Understand failed"
            var summary = ""
            try {
                DebugLog.step("understand", "start provider=${creds.provider.id} model=${creds.model}")
                val result = Understand.run(
                    root,
                    focus,
                    creds.provider,
                    creds.key,
                    creds.model,
                    creds.base,
                    stop = { stopFlag.get() },
                ) { line ->
                    _state.update { s -> s.copy(statusNote = line) }
                    DebugLog.step("understand", line)
                }
                _state.update {
                    it.copy(
                        markdown = result.markdown,
                        statusNote = "${result.defs} defs · ${result.files} files",
                    )
                }
                summary = "Documented ${root.name}: ${result.defs} defs in ${result.files} files"
                log(summary)
                DebugLog.step("understand", "done defs=${result.defs} files=${result.files}")
                title = "Understand finished"
            } catch (t: CancellationException) {
                title = "Understand stopped"
                summary = _state.value.statusNote.ifBlank { "Stopped" }
                DebugLog.step("understand", "cancelled")
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                _state.update { s ->
                    s.copy(
                        statusNote = msg,
                        markdown = s.markdown.ifBlank { "Understand failed: $msg" },
                    )
                }
                summary = msg
                DebugLog.step("understand", "fail ${t.javaClass.simpleName}: $msg")
                log("understand error: $msg")
            } finally {
                stopFlag.set(false)
                activeTask = null
                _state.update { it.copy(running = false, stopping = false) }
            }
            TaskRunner.Done(title, summary)
        }
        activeTask = task
        if (task == null) {
            _state.update { it.copy(running = false, statusNote = "Understand is already running") }
        }
    }

    fun stop() {
        if (!tasks.holds(Res.UNDERSTAND)) return
        stopFlag.set(true)
        _state.update {
            it.copy(stopping = true, statusNote = "Stopping after this step")
        }
        activeTask?.cancel()
    }

    fun save(root: File) {
        val text = _state.value.markdown
        if (text.isBlank()) {
            _state.update { it.copy(statusNote = "Nothing to save yet") }
            return
        }
        try {
            val file = Understand.save(root, text)
            _state.update { it.copy(statusNote = "Saved ${file.name}") }
            log("wrote ${com.example.andvibe.core.RepoFiles.rel(file, root)}")
            effects.tryEmit(Effect.Saved(root))
        } catch (t: Throwable) {
            _state.update { it.copy(statusNote = t.message ?: t.javaClass.simpleName) }
        }
    }

    fun requestPickRepo() {
        effects.tryEmit(Effect.PickRepo)
    }
}
