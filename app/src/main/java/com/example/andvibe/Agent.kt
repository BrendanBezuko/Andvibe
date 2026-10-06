package com.example.andvibe

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class AgentResult(val text: String, val changed: List<File>, val steps: Int)

class AgentJob(
    val ctx: AgentContext,
    val cwd: File,
    val open: File?,
    val task: String,
    val provider: Provider,
    val key: String,
    val model: String,
    val base: String,
    val earlier: List<Pair<String, String>>,
    val stop: AtomicBoolean
)

object Agent {
    private const val MAX_PLAN_STEPS = 12
    private const val MAX_STEPS = 40
    private const val MAX_RESULT = 20_000
    private const val COMPACT_AT = 300_000

    private val planPrompt get() = PromptStore.get(PromptStore.Kind.PLAN)
    private val systemPrompt get() = PromptStore.get(PromptStore.Kind.AGENT)

    fun run(job: AgentJob, onStep: (String) -> Unit): AgentResult {
        if (job.task.length > 16_000) error("prompt is too long")
        if (job.key.isBlank()) error("add an API key in Settings")
        if (job.model.isBlank()) error("set a model name")
        if (job.model.any { it.isWhitespace() }) error("model name has a space")
        val baseUrl = job.base.ifBlank { job.provider.defaultBase }
        if (baseUrl.isBlank()) error("set a base URL")
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
            error("base URL must start with https://")
        }
        val context = firstMessage(job)
        onStep("Planning…")
        val planned = phase(
            job = job,
            model = makeModel(job, baseUrl),
            system = planPrompt,
            tools = AgentTools.planSpecs,
            user = context,
            maxSteps = MAX_PLAN_STEPS,
            onStep = onStep,
            label = "plan"
        )
        if (job.stop.get()) {
            return finish(job, "Stopped during planning. ${planned.text}".trim(), planned.steps)
        }
        val plan = planned.text.trim().ifBlank { "No detailed plan. Explore briefly, then make the smallest change that finishes the task." }
        onStep("Plan ready")
        onStep(plan.take(600))
        val executeUser = buildString {
            append(context)
            append("\n\nAgreed plan:\n")
            append(plan)
            append("\n\nFollow that plan. Explore only what you still need, then edit.")
        }
        onStep("Executing…")
        val done = phase(
            job = job,
            model = makeModel(job, baseUrl),
            system = systemPrompt,
            tools = AgentTools.specs,
            user = executeUser,
            maxSteps = MAX_STEPS,
            onStep = onStep,
            label = "agent"
        )
        val total = planned.steps + done.steps
        if (job.stop.get()) {
            return finish(job, "Stopped. ${done.text}".trim(), total)
        }
        return finish(job, done.text.trim().ifBlank { "Done." }, total)
    }

    private data class PhaseResult(val text: String, val steps: Int)

    private fun phase(
        job: AgentJob,
        model: AgentModel,
        system: String,
        tools: List<ToolSpec>,
        user: String,
        maxSteps: Int,
        onStep: (String) -> Unit,
        label: String
    ): PhaseResult {
        model.start(system, tools, user)
        var lastText = ""
        for (step in 1..maxSteps) {
            if (job.stop.get()) return PhaseResult(lastText, step - 1)
            val turn = model.step()
            val input = if (turn.input > 0) turn.input else AiClient.guessTokens(model.size())
            val output = if (turn.output > 0) turn.output else AiClient.guessTokens(turn.text.length + 200)
            WorkspaceStore.addUse(job.model, input, output)
            UiBridge.usageUpdate()
            if (turn.calls.isEmpty()) {
                return PhaseResult(turn.text.trim().ifBlank { lastText }, step)
            }
            if (turn.text.isNotBlank()) {
                lastText = turn.text.trim()
                onStep(lastText.take(400))
            }
            val results = mutableListOf<ToolResult>()
            for (call in turn.calls) {
                if (job.stop.get()) {
                    results.add(ToolResult(call, "the user stopped the run", true))
                    continue
                }
                onStep("→ " + AgentTools.label(call.name, call.args))
                val result = if (call.badArgs != null) {
                    ToolResult(call, "arguments were not valid JSON: ${call.badArgs.take(300)}", true)
                } else {
                    try {
                        val out = AgentTools.run(call.name, call.args, job.ctx)
                        ToolResult(call, clip(out), false)
                    } catch (t: Throwable) {
                        ToolResult(call, "error: ${t.message ?: t.javaClass.simpleName}", true)
                    }
                }
                DebugLog.step(label, "${call.name} error=${result.error} chars=${result.output.length}")
                summary(call.name, result)?.let { onStep("   $it") }
                if (call.name in WRITES && !result.error) UiBridge.filesChanged()
                results.add(result)
            }
            model.addResults(results)
            if (model.size() > COMPACT_AT) model.compact(keep = 6)
        }
        return PhaseResult(
            lastText.ifBlank { "Stopped after $maxSteps $label steps." },
            maxSteps
        )
    }

    private fun makeModel(job: AgentJob, baseUrl: String): AgentModel {
        return when (job.provider) {
            Provider.ANTHROPIC -> AnthropicModel(baseUrl, job.key, job.model)
            Provider.GEMINI -> GeminiModel(baseUrl, job.key, job.model)
            Provider.OPENAI -> ResponsesModel(baseUrl, job.key, job.model)
            else -> if (baseUrl.contains("api.openai.com")) {
                ResponsesModel(baseUrl, job.key, job.model)
            } else {
                OpenAiModel(baseUrl, job.key, job.model, job.provider)
            }
        }
    }

    private val WRITES = setOf("edit_file", "write_file", "delete_file")

    private fun summary(name: String, result: ToolResult): String? {
        val first = result.output.lineSequence().firstOrNull().orEmpty().take(200)
        return when {
            result.error -> first
            name in WRITES || name == "cloud_build" || name == "run_js_tests" || name == "create_project" -> first
            name == "grep" && first.startsWith("no matches") -> first
            else -> null
        }
    }

    private fun finish(job: AgentJob, text: String, steps: Int): AgentResult {
        return AgentResult(text, job.ctx.changed.toList(), steps)
    }

    private fun clip(text: String): String {
        if (text.length <= MAX_RESULT) return text
        return text.take(MAX_RESULT) + "\n… cut ${text.length - MAX_RESULT} characters. Ask for a smaller range."
    }

    private fun firstMessage(job: AgentJob): String {
        val root = job.ctx.root
        val active = WorkspaceStore.activeRepos()
        val others = active.map { it.name }.filter { it != root?.name }
        val refs = ProjectMentions.dirsIn(job.task, active).filter { it.canonicalFile != root?.canonicalFile }
        return buildString {
            if (root == null) {
                append("Repo: none selected\n")
            } else {
                append("Repo: ").append(root.name).append(" (every tool works only inside this repo)\n")
                append("Working directory: ").append(RepoFiles.rel(job.cwd, root).ifBlank { "." }).append('\n')
                job.open?.takeIf { it.isFile }?.let {
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
                val status = runCatching { GitOps.status(root, job.ctx.repos) }.getOrNull()
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
            if (job.earlier.isNotEmpty()) {
                append("Earlier in this chat:\n")
                for ((role, text) in job.earlier) {
                    append(if (role == "user") "User: " else "You: ").append(text.take(1_500)).append('\n')
                }
                append('\n')
            }
            append("Task:\n").append(job.task)
        }
    }
}

data class ToolCall(val id: String, val name: String, val args: JSONObject, val badArgs: String?)

data class ToolResult(val call: ToolCall, val output: String, val error: Boolean)

data class Turn(val text: String, val calls: List<ToolCall>, val input: Long, val output: Long)

interface AgentModel {
    fun start(system: String, tools: List<ToolSpec>, user: String)
    fun step(): Turn
    fun addResults(results: List<ToolResult>)
    fun size(): Int
    fun compact(keep: Int)
}

private abstract class SlotModel : AgentModel {
    private val slots = mutableListOf<Pair<JSONObject, String>>()

    protected fun slot(obj: JSONObject, key: String) {
        slots.add(obj to key)
    }

    override fun compact(keep: Int) {
        for ((obj, key) in slots.dropLast(keep)) {
            val text = obj.optString(key)
            if (text.length > 400) {
                obj.put(key, "[older output removed to save space] " + text.take(200))
            }
        }
    }

    protected fun parseArgs(raw: String): Pair<JSONObject, String?> {
        if (raw.isBlank()) return JSONObject() to null
        return try {
            JSONObject(raw) to null
        } catch (_: Exception) {
            JSONObject() to raw
        }
    }
}

private class AnthropicModel(
    private val base: String,
    private val key: String,
    private val model: String
) : SlotModel() {
    private var system = ""
    private val tools = JSONArray()
    private val messages = JSONArray()
    private var maxTokens = 16_000
    private var marked: JSONObject? = null

    override fun start(system: String, tools: List<ToolSpec>, user: String) {
        this.system = system
        for (spec in tools) {
            this.tools.put(
                JSONObject()
                    .put("name", spec.name)
                    .put("description", spec.description)
                    .put("input_schema", spec.schema)
            )
        }
        this.tools.optJSONObject(this.tools.length() - 1)
            ?.put("cache_control", JSONObject().put("type", "ephemeral"))
        messages.put(
            JSONObject().put("role", "user").put(
                "content",
                JSONArray().put(JSONObject().put("type", "text").put("text", user))
            )
        )
    }

    override fun step(): Turn {
        markLast()
        val raw = try {
            send()
        } catch (e: IllegalStateException) {
            if (maxTokens > 8_192 && e.message.orEmpty().contains("max_tokens")) {
                maxTokens = 8_192
                send()
            } else {
                throw e
            }
        }
        val json = JSONObject(raw)
        val content = json.optJSONArray("content") ?: JSONArray()
        messages.put(JSONObject().put("role", "assistant").put("content", content))
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> text.append(block.optString("text"))
                "tool_use" -> calls.add(
                    ToolCall(
                        block.optString("id"),
                        block.optString("name"),
                        block.optJSONObject("input") ?: JSONObject(),
                        null
                    )
                )
            }
        }
        if (calls.isEmpty() && json.optString("stop_reason") == "max_tokens") {
            text.append("\n(the reply hit the token limit)")
        }
        val use = AiClient.readUse(raw)
        return Turn(text.toString(), calls, use.first, use.second)
    }

    override fun addResults(results: List<ToolResult>) {
        val blocks = JSONArray()
        for (result in results) {
            val block = JSONObject()
                .put("type", "tool_result")
                .put("tool_use_id", result.call.id)
                .put("content", result.output)
            if (result.error) block.put("is_error", true)
            slot(block, "content")
            blocks.put(block)
        }
        messages.put(JSONObject().put("role", "user").put("content", blocks))
    }

    override fun size(): Int = messages.toString().length + system.length

    private fun send(): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put(
                "system",
                JSONArray().put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", system)
                        .put("cache_control", JSONObject().put("type", "ephemeral"))
                )
            )
            .put("tools", tools)
            .put("messages", messages)
        val headers = mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
        return AiClient.post(AiClient.anthropicUrl(base), headers, body.toString())
    }

    private fun markLast() {
        marked?.remove("cache_control")
        val last = messages.optJSONObject(messages.length() - 1) ?: return
        val content = last.optJSONArray("content") ?: return
        val block = content.optJSONObject(content.length() - 1) ?: return
        block.put("cache_control", JSONObject().put("type", "ephemeral"))
        marked = block
    }
}

private class OpenAiModel(
    private val base: String,
    private val key: String,
    private val model: String,
    private val provider: Provider
) : SlotModel() {
    private val tools = JSONArray()
    private val messages = JSONArray()
    private var tokenField = if (provider == Provider.OPENAI) "max_completion_tokens" else "max_tokens"

    override fun start(system: String, tools: List<ToolSpec>, user: String) {
        for (spec in tools) {
            this.tools.put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject()
                        .put("name", spec.name)
                        .put("description", spec.description)
                        .put("parameters", spec.schema)
                )
            )
        }
        messages.put(JSONObject().put("role", "system").put("content", system))
        messages.put(JSONObject().put("role", "user").put("content", user))
    }

    override fun step(): Turn {
        val raw = try {
            send()
        } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            if (message.contains("max_tokens") || message.contains("max_completion_tokens")) {
                tokenField = if (tokenField == "max_tokens") "max_completion_tokens" else "max_tokens"
                send()
            } else {
                throw e
            }
        }
        val json = JSONObject(raw)
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) error("empty response")
        val message = choices.getJSONObject(0).optJSONObject("message") ?: error("empty response")
        val echo = JSONObject().put("role", "assistant").put("content", message.opt("content") ?: JSONObject.NULL)
        message.optJSONArray("tool_calls")?.let { echo.put("tool_calls", it) }
        message.opt("reasoning_details")?.let { echo.put("reasoning_details", it) }
        messages.put(echo)
        val text = when (val content = message.opt("content")) {
            is String -> content
            is JSONArray -> buildString {
                for (i in 0 until content.length()) append(content.optJSONObject(i)?.optString("text").orEmpty())
            }
            else -> ""
        }
        val calls = mutableListOf<ToolCall>()
        val list = message.optJSONArray("tool_calls")
        if (list != null) {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val fn = item.optJSONObject("function") ?: continue
                val args = parseArgs(fn.optString("arguments"))
                calls.add(ToolCall(item.optString("id"), fn.optString("name"), args.first, args.second))
            }
        }
        val use = AiClient.readUse(raw)
        return Turn(text, calls, use.first, use.second)
    }

    override fun addResults(results: List<ToolResult>) {
        for (result in results) {
            val obj = JSONObject()
                .put("role", "tool")
                .put("tool_call_id", result.call.id)
                .put("content", result.output)
            slot(obj, "content")
            messages.put(obj)
        }
    }

    override fun size(): Int = messages.toString().length

    private fun send(): String {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("tools", tools)
            .put(tokenField, 16_000)
        val headers = mapOf("Authorization" to "Bearer $key") + AiClient.extraHeaders(provider)
        return AiClient.post(AiClient.chatUrl(base), headers, body.toString())
    }
}

private class ResponsesModel(
    private val base: String,
    private val key: String,
    private val model: String
) : SlotModel() {
    private var system = ""
    private val tools = JSONArray()
    private val input = JSONArray()

    override fun start(system: String, tools: List<ToolSpec>, user: String) {
        this.system = system
        for (spec in tools) {
            this.tools.put(
                JSONObject()
                    .put("type", "function")
                    .put("name", spec.name)
                    .put("description", spec.description)
                    .put("parameters", spec.schema)
            )
        }
        input.put(JSONObject().put("role", "user").put("content", user))
    }

    override fun step(): Turn {
        val body = JSONObject()
            .put("model", model)
            .put("instructions", system)
            .put("input", input)
            .put("tools", tools)
            .put("max_output_tokens", 16_000)
        val raw = AiClient.post(
            AiClient.responsesUrl(base),
            mapOf("Authorization" to "Bearer $key"),
            body.toString()
        )
        val json = JSONObject(raw)
        val output = json.optJSONArray("output") ?: error("empty response")
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            input.put(item)
            when (item.optString("type")) {
                "message" -> {
                    val content = item.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        text.append(content.optJSONObject(j)?.optString("text").orEmpty())
                    }
                }
                "function_call" -> {
                    val args = parseArgs(item.optString("arguments"))
                    calls.add(ToolCall(item.optString("call_id"), item.optString("name"), args.first, args.second))
                }
            }
        }
        if (calls.isEmpty() && json.optString("status") == "incomplete") {
            text.append("\n(the reply hit the token limit)")
        }
        val use = AiClient.readUse(raw)
        return Turn(text.toString(), calls, use.first, use.second)
    }

    override fun addResults(results: List<ToolResult>) {
        for (result in results) {
            val obj = JSONObject()
                .put("type", "function_call_output")
                .put("call_id", result.call.id)
                .put("output", result.output)
            slot(obj, "output")
            input.put(obj)
        }
    }

    override fun size(): Int = input.toString().length + system.length
}

private class GeminiModel(
    private val base: String,
    private val key: String,
    model: String
) : SlotModel() {
    private val modelId = model.removePrefix("models/").trim()
    private var system = ""
    private val declarations = JSONArray()
    private val contents = JSONArray()
    private val madeIds = mutableSetOf<String>()

    override fun start(system: String, tools: List<ToolSpec>, user: String) {
        this.system = system
        for (spec in tools) {
            val decl = JSONObject().put("name", spec.name).put("description", spec.description)
            if ((spec.schema.optJSONObject("properties")?.length() ?: 0) > 0) decl.put("parameters", spec.schema)
            declarations.put(decl)
        }
        contents.put(
            JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))
        )
    }

    override fun step(): Turn {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations)))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 16_000))
        val raw = AiClient.post(
            AiClient.geminiUrl(base, modelId),
            mapOf("x-goog-api-key" to key),
            body.toString()
        )
        val json = JSONObject(raw)
        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            error(json.optJSONObject("promptFeedback")?.toString() ?: "empty response")
        }
        val candidate = candidates.getJSONObject(0)
        val content = candidate.optJSONObject("content")
        val parts = content?.optJSONArray("parts")
        if (content == null || parts == null || parts.length() == 0) {
            error("Gemini returned nothing (${candidate.optString("finishReason")})")
        }
        content.put("role", "model")
        contents.put(content)
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue
            val fn = part.optJSONObject("functionCall")
            if (fn != null) {
                val id = fn.optString("id").ifBlank {
                    "local_${madeIds.size + 1}".also { madeIds.add(it) }
                }
                calls.add(ToolCall(id, fn.optString("name"), fn.optJSONObject("args") ?: JSONObject(), null))
            } else {
                text.append(part.optString("text"))
            }
        }
        val use = AiClient.readUse(raw)
        return Turn(text.toString(), calls, use.first, use.second)
    }

    override fun addResults(results: List<ToolResult>) {
        val parts = JSONArray()
        for (result in results) {
            val response = JSONObject().put(if (result.error) "error" else "result", result.output)
            slot(response, if (result.error) "error" else "result")
            val fn = JSONObject().put("name", result.call.name).put("response", response)
            if (result.call.id !in madeIds) fn.put("id", result.call.id)
            parts.put(JSONObject().put("functionResponse", fn))
        }
        contents.put(JSONObject().put("role", "user").put("parts", parts))
    }

    override fun size(): Int = contents.toString().length + system.length
}
