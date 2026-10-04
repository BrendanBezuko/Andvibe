package com.example.andvibe

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque

data class EditResult(val report: String, val written: List<File>)

object AiClient {
    private val systemPrompt = """
        You edit a repo on an Android phone. The phone can preview index.html and run plain JavaScript. Relative require("./file.js") works. npm packages, import/export, Python, Java, Kotlin, Gradle, Rust, and Go do not run on the phone. Tests use assert(cond, msg), assert.equal(a, b), and assert.strictEqual(a, b). Name tests *.test.js or put them in test/.

        Reply with one JSON object and nothing else:
        {"summary":"what changed","files":[{"path":"relative/path.js","content":"the full new file"}]}

        Change the existing code in this repo. Do not rewrite the project from scratch or scaffold a separate app unless the user explicitly asks for a new project.

        Paths are relative to the repo root. Use forward slashes. Never use .. or absolute paths. Include the complete contents of every file you change or create. Omit files you do not change. If no files change, return an empty files array and put the answer in summary. Do not wrap the JSON in markdown.
    """.trimIndent()

    private val sourceExt = setOf(
        "js", "mjs", "cjs", "html", "htm", "css", "json", "md", "txt",
        "py", "kt", "kts", "java", "xml", "gradle", "toml", "yml", "yaml",
        "properties", "svg", "ts", "tsx", "jsx"
    )

    fun edit(
        root: File,
        cwd: File,
        open: File?,
        instruction: String,
        provider: Provider,
        key: String,
        model: String,
        base: String,
        history: ArrayDeque<Pair<String, String>>
    ): EditResult {
        if (instruction.length > 16_000) error("prompt is too long")
        if (key.isBlank()) error("add an API key in Settings")
        if (model.isBlank()) error("set a model name")
        if (model.any { it.isWhitespace() }) error("model name has a space")
        val baseUrl = base.ifBlank { provider.defaultBase }
        if (baseUrl.isBlank()) error("set a base URL")
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
            error("base URL must start with https://")
        }
        val prompt = buildPrompt(root, cwd, open, instruction)
        val messages = sanitize(history.toList() + ("user" to prompt))
        val reply = when (provider) {
            Provider.ANTHROPIC -> anthropic(baseUrl, key, model, messages)
            Provider.GEMINI -> gemini(baseUrl, key, model, messages)
            Provider.CURSOR -> cursor(baseUrl, key, model, messages)
            else -> openaiCompatible(baseUrl, key, model, messages, headers = extraHeaders(provider))
        }
        val charged = if (reply.input == 0L && reply.output == 0L) {
            reply.copy(
                input = guessTokens(prompt.length + systemPrompt.length),
                output = guessTokens(reply.text.length)
            )
        } else {
            reply
        }
        WorkspaceStore.addUse(model, charged.input, charged.output)
        val parsed = parse(reply.text)
        val written = apply(root, parsed.second)
        val summary = parsed.first.ifBlank {
            if (written.isEmpty()) "no file changes" else "updated ${written.size} file(s)"
        }
        history.addLast("user" to instruction.take(2000))
        history.addLast("assistant" to summary.take(2000))
        while (history.size > 8) history.removeFirst()
        val report = buildString {
            append(summary)
            written.forEach { append("\nwrote ").append(RepoFiles.rel(it, root)) }
        }
        return EditResult(report, written)
    }

    fun complete(
        system: String,
        user: String,
        provider: Provider,
        key: String,
        model: String,
        base: String
    ): String {
        if (user.length > 16_000) error("prompt is too long")
        if (key.isBlank()) error("add an API key in Settings")
        if (model.isBlank()) error("set a model name")
        if (model.any { it.isWhitespace() }) error("model name has a space")
        val baseUrl = base.ifBlank { provider.defaultBase }
        if (baseUrl.isBlank()) error("set a base URL")
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
            error("base URL must start with https://")
        }
        val messages = listOf("user" to user)
        val reply = when (provider) {
            Provider.ANTHROPIC -> anthropic(baseUrl, key, model, messages, system)
            Provider.GEMINI -> gemini(baseUrl, key, model, messages, system)
            Provider.CURSOR -> cursor(baseUrl, key, model, messages, system)
            else -> openaiCompatible(baseUrl, key, model, messages, system, extraHeaders(provider))
        }
        val charged = if (reply.input == 0L && reply.output == 0L) {
            reply.copy(input = guessTokens(user.length + system.length), output = guessTokens(reply.text.length))
        } else {
            reply
        }
        WorkspaceStore.addUse(model, charged.input, charged.output)
        return reply.text.trim()
    }

    fun research(
        system: String,
        user: String,
        provider: Provider,
        key: String,
        model: String,
        base: String
    ): String {
        if (user.length > 16_000) error("prompt is too long")
        if (key.isBlank()) error("add an API key in Settings")
        if (model.isBlank()) error("set a model name")
        if (provider == Provider.CURSOR) error("Cursor cannot search the web")
        val baseUrl = base.ifBlank { provider.defaultBase }
        if (baseUrl.isBlank()) error("set a base URL")
        val messages = listOf("user" to user)
        val reply = when (provider) {
            Provider.ANTHROPIC -> anthropic(baseUrl, key, model, messages, system, search = true)
            Provider.GEMINI -> gemini(baseUrl, key, model, messages, system, search = true)
            else -> try {
                responses(baseUrl, key, model, messages, system, extraHeaders(provider), search = true)
            } catch (e: IllegalStateException) {
                chatCompletions(baseUrl, key, model, messages, system, extraHeaders(provider), search = true)
            }
        }
        val charged = if (reply.input == 0L && reply.output == 0L) {
            reply.copy(input = guessTokens(user.length + system.length), output = guessTokens(reply.text.length))
        } else {
            reply
        }
        WorkspaceStore.addUse(model, charged.input, charged.output)
        return reply.text.trim()
    }

    private fun buildPrompt(root: File, cwd: File, open: File?, instruction: String): String {
        val files = chooseFiles(root, open)
        return buildString {
            append("Repo: ").append(root.name).append('\n')
            append("Working directory: ").append(RepoFiles.rel(cwd, root).ifBlank { "." }).append('\n')
            append("\nFile tree:\n")
            append(tree(root))
            append("\n\n")
            if (files.second) {
                append("Large repo: only the open file is included. Open another file if you need it.\n\n")
            }
            if (files.first.isEmpty()) {
                append("No file contents were included.\n\n")
            } else {
                append("Current files (source of truth):\n")
                for ((path, content) in files.first) {
                    append("----- FILE ").append(path).append(" -----\n")
                    append(content)
                    if (!content.endsWith("\n")) append('\n')
                    append("----- END FILE -----\n")
                }
                append('\n')
            }
            append("Instruction:\n")
            append(instruction)
        }
    }

    internal fun tree(root: File, limit: Int = 200): String {
        val lines = mutableListOf<String>()
        fun walkDir(dir: File, prefix: String) {
            if (lines.size >= limit) return
            val kids = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
            for (kid in kids) {
                if (lines.size >= limit) return
                if (kid.name in RepoFiles.SKIP_DIRS) continue
                if (kid.isDirectory) {
                    lines.add("$prefix${kid.name}/")
                    walkDir(kid, prefix + kid.name + "/")
                } else {
                    lines.add("$prefix${kid.name}")
                }
            }
        }
        walkDir(root, "")
        if (lines.size >= limit) lines.add("…")
        return lines.joinToString("\n").ifBlank { "(empty)" }
    }

    private fun chooseFiles(root: File, open: File?): Pair<List<Pair<String, String>>, Boolean> {
        val found = mutableListOf<File>()
        RepoFiles.walk(root) { file ->
            if (found.size >= 40) return@walk
            if (!isSource(file) || file.length() > 32_000 || RepoFiles.looksBinary(file)) return@walk
            found.add(file)
        }
        val total = found.sumOf { it.length() }
        val small = found.size <= 25 && total <= 60_000 && found.size < 40
        val chosen = if (small) found.toMutableList() else mutableListOf()
        if (open != null && open.isFile && open.length() <= 40_000 && !RepoFiles.looksBinary(open)) {
            val inside = try {
                RepoFiles.ensureInside(root, open)
                true
            } catch (_: Exception) {
                false
            }
            if (inside && chosen.none { it.canonicalPath == open.canonicalPath }) {
                if (small) chosen.add(0, open) else chosen.add(open)
            }
        }
        var budget = 70_000
        val out = mutableListOf<Pair<String, String>>()
        for (file in chosen) {
            if (budget <= 0) break
            var text = file.readText()
            if (text.length > budget) text = text.take(budget)
            budget -= text.length
            out.add(RepoFiles.rel(file, root) to text)
        }
        return out to !small
    }

    private fun isSource(file: File): Boolean {
        val name = file.name
        if (name == "package-lock.json" || name == "yarn.lock" || name.endsWith(".min.js")) return false
        return file.extension.lowercase() in sourceExt
    }

    private fun apply(root: File, files: List<Pair<String, String>>): List<File> {
        val written = mutableListOf<File>()
        for ((path, content) in files) {
            if (content.length > 500_000) error("refusing a file over 500KB: $path")
            try {
                val file = RepoFiles.safeChild(root, path)
                file.writeText(content)
                written.add(file)
            } catch (e: Exception) {
                val done = written.joinToString { RepoFiles.rel(it, root) }
                val prefix = if (done.isBlank()) "" else "wrote $done\n"
                error("${prefix}failed on $path: ${e.message}")
            }
        }
        return written
    }

    private fun parse(raw: String): Pair<String, List<Pair<String, String>>> {
        val body = extractJson(raw)
        try {
            val json = JSONObject(body)
            return json.optString("summary") to parseFiles(json)
        } catch (e: JSONException) {
            error("could not parse JSON: ${e.message}\n${raw.take(1200)}")
        }
    }

    private fun extractJson(raw: String): String {
        var text = raw.trim().removePrefix("\uFEFF")
        if (text.startsWith("```")) {
            text = text.substringAfter('\n', text)
            if (text.trimEnd().endsWith("```")) {
                text = text.trimEnd().dropLast(3)
            }
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) error("model did not return JSON:\n${raw.take(1200)}")
        return text.substring(start, end + 1)
    }

    private fun parseFiles(json: JSONObject): List<Pair<String, String>> {
        if (!json.has("files") || json.isNull("files")) return emptyList()
        return when (val value = json.get("files")) {
            is JSONArray -> buildList {
                for (i in 0 until value.length()) {
                    val item = value.opt(i)
                    if (item is JSONObject) add(fileEntry(item)) else error("bad files entry")
                }
            }
            is JSONObject -> buildList {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val path = keys.next()
                    if (value.isNull(path)) error("missing content for $path")
                    add(path to value.getString(path))
                }
            }
            else -> error("files must be a list")
        }
    }

    private fun fileEntry(obj: JSONObject): Pair<String, String> {
        val path = obj.optString("path").ifBlank { obj.optString("file") }.ifBlank { obj.optString("name") }
        if (path.isBlank()) error("file entry missing path")
        val content = when {
            obj.has("content") && !obj.isNull("content") -> obj.getString("content")
            obj.has("contents") && !obj.isNull("contents") -> obj.getString("contents")
            obj.has("text") && !obj.isNull("text") -> obj.getString("text")
            else -> error("missing content for $path")
        }
        return path to content
    }

    private fun sanitize(messages: List<Pair<String, String>>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for ((role, content) in messages) {
            if (content.isBlank()) continue
            val nextRole = if (role == "assistant") "assistant" else "user"
            val last = out.lastOrNull()
            if (last != null && last.first == nextRole) {
                out[out.lastIndex] = nextRole to last.second + "\n\n" + content
            } else {
                out.add(nextRole to content)
            }
        }
        if (out.firstOrNull()?.first == "assistant") out.removeAt(0)
        if (out.isEmpty()) error("empty prompt")
        return out
    }

    private data class Reply(val text: String, val input: Long, val output: Long)

    internal fun guessTokens(chars: Int): Long {
        if (chars <= 0) return 0
        return (chars / 4L).coerceAtLeast(1)
    }

    internal fun readUse(raw: String): Pair<Long, Long> {
        return try {
            val json = JSONObject(raw)
            val usage = json.optJSONObject("usage")
            if (usage != null) {
                val input = when {
                    usage.has("input_tokens") -> usage.optLong("input_tokens")
                    else -> usage.optLong("prompt_tokens")
                }
                val output = when {
                    usage.has("output_tokens") -> usage.optLong("output_tokens")
                    else -> usage.optLong("completion_tokens")
                }
                return input.coerceAtLeast(0) to output.coerceAtLeast(0)
            }
            val meta = json.optJSONObject("usageMetadata")
            if (meta != null) {
                return meta.optLong("promptTokenCount").coerceAtLeast(0) to
                    meta.optLong("candidatesTokenCount").coerceAtLeast(0)
            }
            0L to 0L
        } catch (_: Exception) {
            0L to 0L
        }
    }

    internal fun extraHeaders(provider: Provider): Map<String, String> {
        if (provider != Provider.OPENROUTER) return emptyMap()
        return mapOf("X-Title" to "AndVibe")
    }

    private fun openaiCompatible(
        base: String,
        key: String,
        model: String,
        messages: List<Pair<String, String>>,
        system: String = systemPrompt,
        headers: Map<String, String> = emptyMap()
    ): Reply {
        return try {
            chatCompletions(base, key, model, messages, system, headers)
        } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            if (message.startsWith("HTTP 404") || message.contains("/v1/responses")) {
                responses(base, key, model, messages, system, headers)
            } else {
                throw e
            }
        }
    }

    private fun chatCompletions(
        base: String,
        key: String,
        model: String,
        messages: List<Pair<String, String>>,
        system: String = systemPrompt,
        headers: Map<String, String> = emptyMap(),
        search: Boolean = false
    ): Reply {
        fun once(field: String): Reply {
            val body = JSONObject()
            body.put("model", model)
            val arr = JSONArray()
            arr.put(JSONObject().put("role", "system").put("content", system))
            for ((role, content) in messages) {
                arr.put(JSONObject().put("role", role).put("content", content))
            }
            body.put("messages", arr)
            body.put(field, 8192)
            if (search) {
                if (base.contains("openrouter.ai")) {
                    body.put("plugins", JSONArray().put(JSONObject().put("id", "web")))
                } else {
                    body.put("search_parameters", JSONObject().put("mode", "on"))
                }
            }
            val raw = post(chatUrl(base), mapOf("Authorization" to "Bearer $key") + headers, body.toString())
            val use = readUse(raw)
            return Reply(openAiText(raw), use.first, use.second)
        }
        return try {
            once("max_tokens")
        } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            if (message.contains("max_tokens") || message.contains("max_completion_tokens")) {
                once("max_completion_tokens")
            } else {
                throw e
            }
        }
    }

    private fun responses(
        base: String,
        key: String,
        model: String,
        messages: List<Pair<String, String>>,
        system: String = systemPrompt,
        headers: Map<String, String> = emptyMap(),
        search: Boolean = false
    ): Reply {
        val body = JSONObject()
        body.put("model", model)
        body.put("instructions", system)
        body.put("max_output_tokens", 8192)
        val input = JSONArray()
        for ((role, content) in messages) {
            input.put(JSONObject().put("role", role).put("content", content))
        }
        body.put("input", input)
        if (search) {
            body.put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
        }
        val raw = post(responsesUrl(base), mapOf("Authorization" to "Bearer $key") + headers, body.toString())
        val use = readUse(raw)
        return Reply(responsesText(raw), use.first, use.second)
    }

    private fun anthropic(
        base: String,
        key: String,
        model: String,
        messages: List<Pair<String, String>>,
        system: String = systemPrompt,
        search: Boolean = false
    ): Reply {
        val body = JSONObject()
        body.put("model", model)
        body.put("max_tokens", 8192)
        body.put("system", system)
        val arr = JSONArray()
        for ((role, content) in messages) {
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        body.put("messages", arr)
        val headers = mutableMapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
        if (search) {
            body.put(
                "tools",
                JSONArray().put(
                    JSONObject()
                        .put("type", "web_search_20250305")
                        .put("name", "web_search")
                        .put("max_uses", 5)
                )
            )
            headers["anthropic-beta"] = "web-search-2025-03-05"
        }
        val text = post(anthropicUrl(base), headers, body.toString())
        val use = readUse(text)
        val content = JSONObject(text).optJSONArray("content") ?: error("empty response")
        val out = StringBuilder()
        for (i in 0 until content.length()) {
            out.append(content.optJSONObject(i)?.optString("text").orEmpty())
        }
        if (out.isBlank()) error("empty response")
        return Reply(out.toString(), use.first, use.second)
    }

    private fun gemini(
        base: String,
        key: String,
        model: String,
        messages: List<Pair<String, String>>,
        system: String = systemPrompt,
        search: Boolean = false
    ): Reply {
        val modelId = model.removePrefix("models/").trim()
        val body = JSONObject()
        body.put(
            "systemInstruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
        )
        val contents = JSONArray()
        for ((role, content) in messages) {
            contents.put(
                JSONObject()
                    .put("role", if (role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", content)))
            )
        }
        body.put("contents", contents)
        if (search) {
            body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        }
        body.put("generationConfig", JSONObject().put("maxOutputTokens", 8192))
        val text = post(geminiUrl(base, modelId), mapOf("x-goog-api-key" to key), body.toString())
        val use = readUse(text)
        val json = JSONObject(text)
        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            error(json.optJSONObject("promptFeedback")?.toString() ?: "empty response")
        }
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: error("empty response")
        val out = StringBuilder()
        for (i in 0 until parts.length()) {
            out.append(parts.optJSONObject(i)?.optString("text").orEmpty())
        }
        if (out.isBlank()) error("empty response")
        return Reply(out.toString(), use.first, use.second)
    }

    private fun openAiText(text: String): String {
        val json = JSONObject(text)
        val choices = json.optJSONArray("choices") ?: error("empty response")
        if (choices.length() == 0) error("empty response")
        val message = choices.getJSONObject(0).getJSONObject("message")
        if (message.isNull("content")) {
            val refusal = message.optString("refusal")
            error(if (refusal.isNotBlank()) refusal else "empty response")
        }
        val value = textContent(message.get("content"))
        if (value.isBlank()) error("empty response")
        return value
    }

    private fun responsesText(text: String): String {
        val json = JSONObject(text)
        val direct = json.optString("output_text")
        if (direct.isNotBlank()) return direct
        val output = json.optJSONArray("output") ?: error("empty response")
        val out = StringBuilder()
        for (i in 0 until output.length()) {
            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                out.append(content.optJSONObject(j)?.optString("text").orEmpty())
            }
        }
        if (out.isBlank()) error("empty response")
        return out.toString()
    }

    private fun textContent(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> buildString {
            for (i in 0 until value.length()) {
                when (val part = value.opt(i)) {
                    is String -> append(part)
                    is JSONObject -> append(part.optString("text"))
                }
            }
        }
        else -> value?.toString().orEmpty()
    }

    internal fun chatUrl(base: String): String {
        val trimmed = base.trim().trimEnd('/')
        return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }

    internal fun responsesUrl(base: String): String {
        val trimmed = base.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/responses") -> trimmed
            trimmed.endsWith("/chat/completions") -> trimmed.removeSuffix("/chat/completions") + "/responses"
            else -> "$trimmed/responses"
        }
    }

    internal fun anthropicUrl(base: String): String {
        val trimmed = base.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/v1/messages") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/messages"
            else -> "$trimmed/v1/messages"
        }
    }

    internal fun geminiUrl(base: String, model: String): String {
        val trimmed = base.trim().trimEnd('/')
        if (trimmed.contains(":generateContent")) return trimmed
        return "$trimmed/models/$model:generateContent"
    }

    private fun cursor(
        base: String,
        key: String,
        model: String,
        messages: List<Pair<String, String>>,
        system: String = systemPrompt
    ): Reply {
        val body = JSONObject()
        body.put("name", "AndVibe")
        body.put("mode", "plan")
        body.put("model", JSONObject().put("id", model))
        body.put("prompt", JSONObject().put("text", cursorPrompt(system, messages)))
        val created = JSONObject(post(cursorUrl(base, "/v1/agents"), cursorHeaders(key), body.toString()))
        val agent = created.optJSONObject("agent") ?: error("Cursor did not return an agent")
        val run = created.optJSONObject("run") ?: error("Cursor did not return a run")
        val agentId = agent.optString("id")
        val runId = run.optString("id")
        if (agentId.isBlank() || runId.isBlank()) error("Cursor did not return a run id")
        val page = agent.optString("url").ifBlank { agentId }
        try {
            val text = waitForCursor(base, key, agentId, runId, page)
            val usage = cursorUsage(base, key, agentId, runId)
            return Reply(text, usage.first, usage.second)
        } finally {
            runCatching {
                post(cursorUrl(base, "/v1/agents/$agentId/archive"), cursorHeaders(key), "{}")
            }
        }
    }

    private fun cursorPrompt(system: String, messages: List<Pair<String, String>>): String {
        return buildString {
            append(system)
            append("\n\n")
            append(
                "This request has no repository. Do not use tools, do not run commands, and do not edit files. " +
                    "The phone applies file changes from your reply. Reply with only the requested text.\n\n"
            )
            for ((role, content) in messages) {
                append(role).append(":\n").append(content).append("\n\n")
            }
        }
    }

    private fun waitForCursor(base: String, key: String, agentId: String, runId: String, page: String): String {
        val deadline = System.currentTimeMillis() + 360_000L
        while (System.currentTimeMillis() < deadline) {
            val json = JSONObject(get(cursorUrl(base, "/v1/agents/$agentId/runs/$runId"), cursorHeaders(key)))
            when (json.optString("status").uppercase()) {
                "FINISHED" -> {
                    val text = json.optString("result")
                    if (text.isBlank()) error("empty response")
                    return text
                }
                "ERROR", "CANCELLED", "EXPIRED" -> {
                    val detail = json.optString("result").ifBlank { json.optString("status") }
                    error("Cursor run ${json.optString("status")}: $detail\n$page")
                }
            }
            try {
                Thread.sleep(2_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                error("Cursor run interrupted\n$page")
            }
        }
        error("Cursor run timed out\n$page")
    }

    private fun cursorUsage(base: String, key: String, agentId: String, runId: String): Pair<Long, Long> {
        return try {
            val json = JSONObject(
                get(cursorUrl(base, "/v1/agents/$agentId/usage?runId=$runId"), cursorHeaders(key))
            )
            val runs = json.optJSONArray("runs")
            val usage = runs?.optJSONObject(0)?.optJSONObject("usage")
                ?: json.optJSONObject("totalUsage")
            val input = usage?.optLong("inputTokens")?.coerceAtLeast(0) ?: 0L
            val output = usage?.optLong("outputTokens")?.coerceAtLeast(0) ?: 0L
            input to output
        } catch (_: Exception) {
            0L to 0L
        }
    }

    private fun cursorHeaders(key: String): Map<String, String> {
        return mapOf("Authorization" to "Bearer $key")
    }

    private fun cursorUrl(base: String, path: String): String {
        val trimmed = base.trim().trimEnd('/')
        val root = when {
            trimmed.endsWith("/v1") -> trimmed.removeSuffix("/v1")
            else -> trimmed
        }
        return root + path
    }

    internal fun post(url: String, headers: Map<String, String>, body: String): String {
        return http("POST", url, headers, body, 180_000)
    }

    private fun get(url: String, headers: Map<String, String>): String {
        return http("GET", url, headers, null, 30_000)
    }

    private fun http(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        readTimeout: Int
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = readTimeout
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "AndVibe")
            headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error(httpError(code, text))
            return text
        } catch (e: IOException) {
            error("network: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun httpError(code: Int, body: String): String {
        val message = try {
            val json = JSONObject(body)
            val err = json.optJSONObject("error")
            when {
                err != null && err.optString("message").isNotBlank() -> err.optString("message")
                json.optString("message").isNotBlank() -> json.optString("message")
                else -> null
            }
        } catch (_: Exception) {
            null
        }
        return "HTTP $code: ${message ?: body.take(600)}"
    }
}
