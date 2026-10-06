package com.example.andvibe.agent

import com.example.andvibe.DebugLog
import com.example.andvibe.core.RepoFiles
import com.example.andvibe.tasks.TaskRunner
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class AgentRun internal constructor(
    val events: Flow<AgentEvent>,
    private val task: TaskRunner.Task,
    private val stop: AtomicBoolean,
) {
    val label: String get() = task.label

    /** Stop after the current model call returns (invariant 4). */
    fun cancel() {
        stop.set(true)
        task.cancel()
    }

    fun isStopping(): Boolean = stop.get()
}

/**
 * Owns agent exclusivity: [launchAgent] must claim Res.AGENT (wired in AppGraph).
 * A second [start] while one is active fails fast (returns null).
 */
class AgentRuntime(
    private val tools: ToolRegistry,
    /** Claims Res.AGENT and runs [block] on the agent dispatcher. Null if busy. */
    private val launchAgent: (block: suspend () -> TaskRunner.Done?) -> TaskRunner.Task?,
) {
    private val current = AtomicReference<AgentRun?>(null)

    fun current(): AgentRun? = current.get()
    fun isStopping(): Boolean = current.get()?.isStopping() == true

    /**
     * Starts a run. Returns null when an agent is already active.
     * [onEvent] runs on the agent worker thread (Phase-3 Vibe adapter).
     */
    fun start(
        request: AgentStart,
        onEvent: (AgentEvent) -> Unit = {},
    ): AgentRun? {
        val stop = AtomicBoolean(false)
        val bus = MutableSharedFlow<AgentEvent>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val emitBridge: suspend (AgentEvent) -> Unit = { event ->
            bus.emit(event)
            onEvent(event)
        }
        val holder = AtomicReference<AgentRun?>(null)
        val task = launchAgent {
            val ctx = AgentContext(request.root, request.repos, request.onRootChanged)
            val job = AgentRequest(
                ctx = ctx,
                cwd = request.cwd,
                open = request.open,
                task = request.task,
                provider = request.provider,
                key = request.key,
                model = request.model,
                base = request.base,
                earlier = request.earlier,
                workspaceRepos = request.workspaceRepos,
                stop = stop,
                http = request.http,
            )
            var title = "Agent error"
            try {
                DebugLog.step(
                    "agent",
                    "start provider=${request.provider.id} model=${request.model} chars=${request.task.length}"
                )
                val result = AgentLoop.run(job, tools, emitBridge)
                val report = formatReport(result, ctx.root)
                val written = result.changed.filter { it.isFile }.map { it.canonicalPath }
                emitBridge(AgentEvent.Done(report, written, result.stopped))
                title = if (result.stopped) "Agent stopped" else "Agent finished"
                DebugLog.step("agent", "done steps=${result.steps} files=${result.changed.size}")
                TaskRunner.Done(title, report)
            } catch (t: CancellationException) {
                val changed = ctx.changed.toList()
                val report = formatReport(
                    AgentResult("Stopped.", changed, 0, stopped = true),
                    ctx.root,
                )
                val written = changed.filter { it.isFile }.map { it.canonicalPath }
                emitBridge(AgentEvent.Done(report, written, stopped = true))
                DebugLog.step("agent", "cancelled files=${changed.size}")
                TaskRunner.Done("Agent stopped", report)
            } catch (t: Throwable) {
                val changed = ctx.changed.toList()
                val msg = formatError(t, ctx.root, changed)
                val written = changed.filter { it.isFile }.map { it.canonicalPath }
                emitBridge(AgentEvent.Failed(msg, written))
                DebugLog.step("agent", "fail ${t.javaClass.simpleName}: $msg")
                TaskRunner.Done(title, msg)
            } finally {
                holder.get()?.let { current.compareAndSet(it, null) }
            }
        } ?: return null
        val run = AgentRun(bus.asSharedFlow(), task, stop)
        holder.set(run)
        current.set(run)
        return run
    }

    private fun formatReport(result: AgentResult, root: File?): String {
        return buildString {
            append(result.text)
            if (root != null && result.changed.isNotEmpty()) {
                append("\n\nChanged in ").append(root.name).append(':')
                result.changed.forEach { file ->
                    append("\n").append(RepoFiles.rel(file, root))
                    if (!file.exists()) append(" (deleted)")
                }
            }
        }
    }

    private fun formatError(t: Throwable, root: File?, changed: List<File>): String {
        return buildString {
            append(t.message ?: t.javaClass.simpleName)
            if (root != null && changed.isNotEmpty()) {
                append("\n\nChanged in ").append(root.name).append(" before the error:")
                changed.forEach { append("\n").append(RepoFiles.rel(it, root)) }
            }
        }
    }
}

data class AgentStart(
    val root: File?,
    val repos: File,
    val cwd: File,
    val open: File?,
    val task: String,
    val provider: com.example.andvibe.Provider,
    val key: String,
    val model: String,
    val base: String,
    val earlier: List<Pair<String, String>>,
    val workspaceRepos: List<File>,
    val onRootChanged: (File) -> Unit = {},
    val http: HttpPost? = null,
)
