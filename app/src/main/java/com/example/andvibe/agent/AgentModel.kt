package com.example.andvibe.agent

import com.example.andvibe.AiClient
import com.example.andvibe.Provider
import org.json.JSONArray
import org.json.JSONObject

/** Blocking HTTP used by model adapters — inject fakes in JVM tests. */
fun interface HttpPost {
    fun post(url: String, headers: Map<String, String>, body: String): String
}

interface AgentModel {
    fun start(system: String, tools: List<ToolSpec>, user: String)
    fun step(): Turn
    fun addResults(results: List<ToolResult>)
    fun size(): Int
    fun compact(keep: Int)
}

object AgentModels {
    fun create(
        provider: Provider,
        key: String,
        model: String,
        baseUrl: String,
        http: HttpPost = HttpPost { url, headers, body -> AiClient.post(url, headers, body) },
    ): AgentModel {
        return when (provider) {
            Provider.ANTHROPIC -> AnthropicModel(baseUrl, key, model, http)
            Provider.GEMINI -> GeminiModel(baseUrl, key, model, http)
            Provider.OPENAI -> ResponsesModel(baseUrl, key, model, http)
            else -> if (baseUrl.contains("api.openai.com")) {
                ResponsesModel(baseUrl, key, model, http)
            } else {
                OpenAiModel(baseUrl, key, model, provider, http)
            }
        }
    }
}

internal abstract class SlotModel : AgentModel {
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

internal class AnthropicModel(
    private val base: String,
    private val key: String,
    private val model: String,
    private val http: HttpPost,
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
        return http.post(AiClient.anthropicUrl(base), headers, body.toString())
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

internal class OpenAiModel(
    private val base: String,
    private val key: String,
    private val model: String,
    private val provider: Provider,
    private val http: HttpPost,
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
        return http.post(AiClient.chatUrl(base), headers, body.toString())
    }
}

internal class ResponsesModel(
    private val base: String,
    private val key: String,
    private val model: String,
    private val http: HttpPost,
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
        val raw = http.post(
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

internal class GeminiModel(
    private val base: String,
    private val key: String,
    model: String,
    private val http: HttpPost,
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
        val raw = http.post(
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
