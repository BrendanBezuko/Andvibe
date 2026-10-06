package com.example.andvibe.agent

import com.example.andvibe.AiClient
import com.example.andvibe.DebugLog
import com.example.andvibe.ProjectMentions
import com.example.andvibe.PromptStore
import com.example.andvibe.Provider
import com.example.andvibe.core.GitOps
import com.example.andvibe.core.RepoFiles
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

data class AgentRequest(
    val ctx: AgentContext,
    val cwd: File,
    val open: File?,
    val task: String,
    val provider: Provider,
    val key: String,
    val model: String,
    val base: String,
    val earlier: List<Pair<String, String>>,
    val workspaceRepos: List<File>,
    val stop: AtomicBoolean,
    val http: HttpPost? = null,
)

/**
 * Plan then execute phases as a suspend function (DESIGN.md §3.7).
 * Cancellation: [AgentRequest.stop] plus [ensureActive] at the same points the
 * old stop flag was checked. Blocking model HTTP is not interrupted mid-call.
 */
object AgentLoop {
    private const val MAX_PLAN_STEPS = 12
    private const val MAX_STEPS = 40
    private const val MAX_RESULT = 20_000
    private const val COMPACT_AT = 300_000
    private val WRITES = setOf("edit_file", "write_file", "delete_file")

    suspend fun run(
        request: AgentRequest,
        tools: ToolRegistry,
        emit: suspend (AgentEvent) -> Unit,
    ): AgentResult {
        if (request.task.length > 16_000) error("prompt is too long")
        if (request.key.isBlank()) error("add an API key in Settings")
        if (request.model.isBlank()) error("set a model name")
        if (request.model.any { it.isWhitespace() }) error("model name has a space")
        val baseUrl = request.base.ifBlank { request.provider.defaultBase }
        if (baseUrl.isBlank()) error("set a base URL")
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
            error("base URL must start with https://")
        }
        val context = firstMessage(request)
        emit(AgentEvent.Step("Planning…"))
        val planned = phase(
            request = request,
            tools = tools,
            model = makeModel(request, baseUrl),
            system = PromptStore.get(PromptStore.Kind.PLAN),
            toolSpecs = tools.planSpecs,
            user = context,
            maxSteps = MAX_PLAN_STEPS,
            emit = emit,
            label = "plan",
        )
        if (stopped(request)) {
            return finish(request, "Stopped during planning. ${planned.text}".trim(), planned.steps, true)
        }
        val plan = planned.text.trim().ifBlank {
            "No detailed plan. Explore briefly, then make the smallest change that finishes the task."
        }
        emit(AgentEvent.Step("Plan ready"))
        emit(AgentEvent.Step(plan.take(600)))
        val executeUser = buildString {
            append(context)
            append("\n\nAgreed plan:\n")
            append(plan)
            append("\n\nFollow that plan. Explore only what you still need, then edit.")
        }
        emit(AgentEvent.Step("Executing…"))
        val done = phase(
            request = request,
            tools = tools,
            model = makeModel(request, baseUrl),
            system = PromptStore.get(PromptStore.Kind.AGENT),
            toolSpecs = tools.specs,
            user = executeUser,
            maxSteps = MAX_STEPS,
            emit = emit,
            label = "agent",
        )
        val total = planned.steps + done.steps
        if (stopped(request)) {
            return finish(request, "Stopped. ${done.text}".trim(), total, true)
        }
        return finish(request, done.text.trim().ifBlank { "Done." }, total, false)
    }

    private data class PhaseResult(val text: String, val steps: Int)

    private suspend fun phase(
        request: AgentRequest,
        tools: ToolRegistry,
        model: AgentModel,
        system: String,
        toolSpecs: List<ToolSpec>,
        user: String,
        maxSteps: Int,
        emit: suspend (AgentEvent) -> Unit,
        label: String,
    ): PhaseResult {
        model.start(system, toolSpecs, user)
        var lastText = ""
        for (step in 1..maxSteps) {
            if (stopped(request)) return PhaseResult(lastText, step - 1)
            coroutineContext.ensureActive()
            val turn = model.step() // not cancellable mid-call
            val input = if (turn.input > 0) turn.input else AiClient.guessTokens(model.size())
            val output = if (turn.output > 0) turn.output else AiClient.guessTokens(turn.text.length + 200)
            emit(AgentEvent.Usage(request.model, input, output))
            if (turn.calls.isEmpty()) {
                return PhaseResult(turn.text.trim().ifBlank { lastText }, step)
            }
            if (turn.text.isNotBlank()) {
                lastText = turn.text.trim()
                emit(AgentEvent.Step(lastText.take(400)))
            }
            val results = mutableListOf<ToolResult>()
            for (call in turn.calls) {
                if (stopped(request)) {
                    results.add(ToolResult(call, "the user stopped the run", true))
                    continue
                }
                coroutineContext.ensureActive()
                val summary = tools.label(call.name, call.args)
                emit(AgentEvent.ToolCall(call.name, summary))
                emit(AgentEvent.Step("→ $summary"))
                val result = if (call.badArgs != null) {
                    ToolResult(call, "arguments were not valid JSON: ${call.badArgs.take(300)}", true)
                } else {
                    try {
                        val out = tools.run(call.name, call.args, request.ctx)
                        ToolResult(call, clip(out), false)
                    } catch (t: Throwable) {
                        ToolResult(call, "error: ${t.message ?: t.javaClass.simpleName}", true)
                    }
                }
                DebugLog.step(label, "${call.name} error=${result.error} chars=${result.output.length}")
                stepSummary(call.name, result)?.let { emit(AgentEvent.Step("   $it")) }
                if (call.name in WRITES && !result.error) {
                    val paths = request.ctx.changed.map { it.absolutePath }
                    emit(AgentEvent.FilesChanged(paths))
                }
                results.add(result)
            }
            model.addResults(results)
            if (model.size() > COMPACT_AT) model.compact(keep = 6)
        }
        return PhaseResult(
            lastText.ifBlank { "Stopped after $maxSteps $label steps." },
            maxSteps,
        )
    }

    private fun makeModel(request: AgentRequest, baseUrl: String): AgentModel {
        return AgentModels.create(
            request.provider,
            request.key,
            request.model,
            baseUrl,
            request.http ?: HttpPost { url, headers, body -> AiClient.post(url, headers, body) },
        )
    }

    private fun stepSummary(name: String, result: ToolResult): String? {
        val first = result.output.lineSequence().firstOrNull().orEmpty().take(200)
        return when {
            result.error -> first
            name in WRITES || name == "cloud_build" || name == "run_js_tests" || name == "create_project" -> first
            name == "grep" && first.startsWith("no matches") -> first
            else -> null
        }
    }

    private fun finish(request: AgentRequest, text: String, steps: Int, stopped: Boolean): AgentResult {
        return AgentResult(text, request.ctx.changed.toList(), steps, stopped)
    }

    private fun clip(text: String): String {
        if (text.length <= MAX_RESULT) return text
        return text.take(MAX_RESULT) + "\n… cut ${text.length - MAX_RESULT} characters. Ask for a smaller range."
    }

    private fun stopped(request: AgentRequest): Boolean = request.stop.get()

    private fun firstMessage(request: AgentRequest): String {
        val root = request.ctx.root
        val others = request.workspaceRepos.map { it.name }.filter { it != root?.name }
        val refs = ProjectMentions.dirsIn(request.task, request.workspaceRepos)
            .filter { it.canonicalFile != root?.canonicalFile }
        return buildString {
            if (root == null) {
                append("Repo: none selected\n")
            } else {
                append("Repo: ").append(root.name).append(" (every tool works only inside this repo)\n")
                append("Working directory: ").append(RepoFiles.rel(request.cwd, root).ifBlank { "." }).append('\n')
                request.open?.takeIf { it.isFile }?.let {
                    append("Open in the editor: ").append(RepoFiles.rel(it, root)).append('\n')
                }
                append("Gradle wrapper: ").append(if (File(root, "gradlew").isFile) "yes, cloud_build works" else "no").append('\n')
            }
            if (others.isNotEmpty()) {
                append("Other repos in this workspace (reference with @name; tools cannot edit them): ")
                    .append(others.joinToString(", ")).append('\n')
            }
            if (root != null) {
                append("\nFile tree (partial):\n").append(AiClient.tree(root, 150)).append("\n\n")
                val status = runCatching { GitOps.status(root, request.ctx.repos) }.getOrNull()
                if (!status.isNullOrBlank()) append("Git status:\n").append(status.take(3_000)).append("\n\n")
                for (name in listOf("AGENTS.md", "CLAUDE.md", ".cursorrules")) {
                    val file = File(root, name)
                    if (file.isFile && file.length() < 200_000) {
                        append("Repo instructions from ").append(name).append(":\n")
                        append(file.readText().take(6_000)).append("\n\n")
                        break
                    }
                }
            } else {
                append('\n')
            }
            for (ref in refs) {
                append(ProjectMentions.contextBlock(ref))
            }
            if (request.earlier.isNotEmpty()) {
                append("Earlier in this chat:\n")
                for ((role, text) in request.earlier) {
                    append(if (role == "user") "User: " else "You: ").append(text.take(1_500)).append('\n')
                }
                append('\n')
            }
            append("Task:\n").append(request.task)
        }
    }
}
