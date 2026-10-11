package com.example.andvibe

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON-RPC side of DebugMcp: initialize, ping, tools/list, tools/call.
 * [callTool] returns a tool result object (see [ok]/[error]); anything it throws
 * becomes an isError result rather than a transport failure.
 */
internal class McpRpc(private val callTool: (name: String, args: JSONObject) -> JSONObject) {

    /** The serialized response, or null for a notification (no reply expected). */
    fun dispatch(body: String): String? {
        val message = try {
            JSONObject(body)
        } catch (_: Exception) {
            return rpcError(null, -32700, "parse error")
        }
        val method = message.optString("method")
        val id = if (message.has("id") && !message.isNull("id")) message.get("id") else null
        if (id == null && method.startsWith("notifications/")) return null
        val result = when (method) {
            "initialize" -> initialize(message.optJSONObject("params"))
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", tools())
            "tools/call" -> call(message.optJSONObject("params"))
            else -> return rpcError(id, -32601, "method not found: $method")
        }
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("result", result)
            .toString()
    }

    private fun call(params: JSONObject?): JSONObject {
        val name = params?.optString("name").orEmpty()
        val args = params?.optJSONObject("arguments") ?: JSONObject()
        return try {
            callTool(name, args)
        } catch (t: Throwable) {
            error(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun initialize(params: JSONObject?): JSONObject {
        val requested = params?.optString("protocolVersion").orEmpty()
        val version = requested.ifBlank { DEFAULT_PROTOCOL }
        return JSONObject()
            .put("protocolVersion", version)
            .put("capabilities", JSONObject().put("tools", JSONObject()))
            .put("serverInfo", JSONObject().put("name", "andvibe").put("version", "1.0"))
    }

    companion object {
        const val DEFAULT_PROTOCOL = "2025-03-26"

        fun tools(): JSONArray = JSONArray()
            .put(tool("logs", "Verbose step log. Areas: console, build, cloud, cloud.zip, cloud.http, cloud.save, git, vibe, import, pack, mcp.", schema(
                "area" to "Filter by area name or prefix. Empty returns every area.",
                "limit" to "How many of the newest matching lines to return. Default 200."
            )))
            .put(tool("build_log", "The Build tab text, including local/remote Gradle logs and the APK path.", schema()))
            .put(tool("console_log", "The Console tab text.", schema()))
            .put(tool("state", "Current project, busy flags, workspace, git, and last APK path. Does not include API keys.", schema()))
            .put(tool("screenshot", "PNG of the AndVibe window. Returns the image and saves it on the device with an adb pull command to copy it to the computer. AndVibe must be open on screen.", schema(
                "tab" to "Switch to this tab first: console, board, files, search, git, vibe, or build. Empty captures what is on screen now.",
                "name" to "File name for the saved PNG, letters, digits, dash, and underscore. Default is the tab (or \"screen\") and a timestamp."
            )))

        fun ok(text: String): JSONObject = result(text, isError = false)

        fun error(text: String): JSONObject = result(text, isError = true)

        /** Saved-PNG base name: sanitized [requested], else "[label]-[stamp]". */
        fun screenshotName(requested: String, label: String, stamp: String): String =
            requested.trim().removeSuffix(".png")
                .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                .take(60)
                .ifEmpty { "$label-$stamp" }

        private fun result(text: String, isError: Boolean): JSONObject = JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .put("isError", isError)

        private fun tool(name: String, description: String, schema: JSONObject): JSONObject =
            JSONObject().put("name", name).put("description", description).put("inputSchema", schema)

        private fun schema(vararg fields: Pair<String, String>): JSONObject {
            val props = JSONObject()
            for ((name, description) in fields) {
                val type = if (name == "limit") "integer" else "string"
                props.put(name, JSONObject().put("type", type).put("description", description))
            }
            return JSONObject().put("type", "object").put("properties", props)
        }

        private fun rpcError(id: Any?, code: Int, message: String): String = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()
    }
}
