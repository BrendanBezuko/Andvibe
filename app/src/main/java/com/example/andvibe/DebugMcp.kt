package com.example.andvibe

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object DebugMcp {
    const val PORT = 8765
    private val started = AtomicBoolean(false)

    @Volatile
    private var listening = false

    @Volatile
    private var failure: String? = null

    @Volatile
    private var lastRequestAt = 0L

    fun statusText(): String {
        val fail = failure
        val inUse = fail?.contains("EADDRINUSE") == true || fail?.contains("Address already in use") == true
        val head = when {
            listening -> "Listening on 127.0.0.1:$PORT"
            inUse -> "Not listening. Port $PORT is already in use on this device."
            !fail.isNullOrBlank() -> "Not listening. $fail"
            else -> "Starting"
        }
        val seen = lastRequestAt
        val whenLine = if (seen == 0L) {
            "No request yet"
        } else {
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
            "Last request ${fmt.format(Date(seen))}"
        }
        val forward = "adb forward tcp:$PORT tcp:$PORT"
        val command = if (inUse) {
            "adb reverse --remove tcp:$PORT\n$forward"
        } else {
            forward
        }
        return "$head\n$whenLine\n$command"
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            try {
                val server = ServerSocket(PORT, 20, InetAddress.getByName("127.0.0.1"))
                listening = true
                failure = null
                DebugLog.step("mcp", "listening on 127.0.0.1:$PORT")
                publish()
                while (true) {
                    val socket = server.accept()
                    Thread({ handle(socket) }, "andvibe-mcp-conn").apply { isDaemon = true }.start()
                }
            } catch (t: Throwable) {
                listening = false
                failure = t.message ?: t.javaClass.simpleName
                started.set(false)
                DebugLog.step("mcp", "listen failed: ${failure}")
                publish()
            }
        }, "andvibe-mcp").apply { isDaemon = true }.start()
    }

    private fun publish() {
        UiBridge.mcpUpdate()
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        try {
            val input = socket.getInputStream()
            val request = readRequest(input) ?: return
            lastRequestAt = System.currentTimeMillis()
            publish()
            if (request.path.substringBefore('?') != "/mcp") {
                writeResponse(socket, 404, "application/json", """{"error":"not found"}""")
                return
            }
            if (request.method != "POST") {
                writeResponse(socket, 405, "application/json", """{"error":"use POST"}""")
                return
            }
            val response = dispatch(request.body)
            val extra = mapOf("Mcp-Session-Id" to "andvibe")
            if (response == null) {
                writeResponse(socket, 202, "application/json", "", extra)
                return
            }
            val sse = request.headers["accept"].orEmpty().contains("text/event-stream")
            if (sse) {
                writeResponse(
                    socket,
                    200,
                    "text/event-stream",
                    "event: message\ndata: $response\n\n",
                    extra + mapOf("Cache-Control" to "no-cache")
                )
            } else {
                writeResponse(socket, 200, "application/json", response, extra)
            }
        } catch (t: Throwable) {
            DebugLog.step("mcp", "request failed: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun dispatch(body: String): String? {
        val message = try {
            JSONObject(body)
        } catch (t: Exception) {
            return rpcError(null, -32700, "parse error")
        }
        val method = message.optString("method")
        val id = if (message.has("id") && !message.isNull("id")) message.get("id") else null
        if (id == null && method.startsWith("notifications/")) return null
        val result = when (method) {
            "initialize" -> initialize(message.optJSONObject("params"))
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", tools())
            "tools/call" -> callTool(message.optJSONObject("params"))
            else -> return rpcError(id, -32601, "method not found: $method")
        }
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("result", result)
            .toString()
    }

    private fun initialize(params: JSONObject?): JSONObject {
        val requested = params?.optString("protocolVersion").orEmpty()
        val version = if (requested.isBlank()) "2025-03-26" else requested
        return JSONObject()
            .put("protocolVersion", version)
            .put("capabilities", JSONObject().put("tools", JSONObject()))
            .put(
                "serverInfo",
                JSONObject().put("name", "andvibe").put("version", "1.0")
            )
    }

    private fun tools(): JSONArray {
        return JSONArray()
            .put(tool("logs", "Verbose step log. Areas: console, build, cloud, cloud.zip, cloud.http, cloud.save, git, vibe, import, pack, mcp.", schema(
                "area" to "Filter by area name or prefix. Empty returns every area.",
                "limit" to "How many of the newest matching lines to return. Default 200."
            )))
            .put(tool("build_log", "The Build tab text, including the Cloud Run upload and the APK path.", schema()))
            .put(tool("console_log", "The Console tab text.", schema()))
            .put(tool("state", "Current project, tab, busy flags, workspace, and last APK path. Does not include API keys.", schema()))
    }

    private fun callTool(params: JSONObject?): JSONObject {
        val name = params?.optString("name").orEmpty()
        val args = params?.optJSONObject("arguments") ?: JSONObject()
        val text = try {
            when (name) {
                "logs" -> DebugLog.text(args.optString("area", ""), args.optInt("limit", 200))
                "build_log" -> AppState.buildText().ifBlank { "build log is empty" }
                "console_log" -> AppState.text().ifBlank { "console log is empty" }
                "state" -> stateText()
                else -> return toolError("unknown tool: $name")
            }
        } catch (t: Throwable) {
            return toolError(t.message ?: t.javaClass.simpleName)
        }
        return toolOk(text)
    }

    private fun stateText(): String {
        if (!AppState.isReady()) return "ready=false\nmcp=127.0.0.1:$PORT\nmcpListening=$listening"
        val snap = AppState.gitSnapshot
        return buildString {
            append("ready=true\n")
            append("mcp=127.0.0.1:$PORT\n")
            append("mcpListening=$listening\n")
            append("tab=${AppState.tab}\n")
            append("cwd=${AppState.cwd.absolutePath}\n")
            append("open=${AppState.openFile?.absolutePath.orEmpty()}\n")
            append("buildBusy=${AppState.buildBusy}\n")
            append("gitBusy=${AppState.gitBusy}\n")
            append("vibeBusy=${AppState.vibeBusy}\n")
            append("lastApk=${AppState.lastApk.orEmpty()}\n")
            val ws = WorkspaceStore.current()
            append("workspace=${ws.name}\n")
            append("workspaceRepos=${ws.repos.sorted().joinToString(",")}\n")
            append("usageTokens=${ws.inputTokens + ws.outputTokens}\n")
            append("usageUsd=${WorkspaceStore.priceText(ws.costMicros)}\n")
            append("boardCards=${ws.cards.size}\n")
            if (snap != null) {
                append("gitBranch=${snap.branch}\n")
                append("gitRepo=${snap.isRepo}\n")
                append("gitChanges=${snap.changes.size}\n")
                append("gitSummary=${snap.summary.replace("\n", " | ")}\n")
            }
        }.trimEnd()
    }

    private fun tool(name: String, description: String, schema: JSONObject): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("inputSchema", schema)
    }

    private fun schema(vararg fields: Pair<String, String>): JSONObject {
        val props = JSONObject()
        for ((name, description) in fields) {
            val type = if (name == "limit") "integer" else "string"
            props.put(name, JSONObject().put("type", type).put("description", description))
        }
        return JSONObject().put("type", "object").put("properties", props)
    }

    private fun toolOk(text: String): JSONObject {
        return JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .put("isError", false)
    }

    private fun toolError(text: String): JSONObject {
        return JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .put("isError", true)
    }

    private fun rpcError(id: Any?, code: Int, message: String): String {
        return JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private fun readRequest(input: InputStream): Request? {
        val header = readUntilHeaderEnd(input) ?: return null
        val text = header.toString(Charsets.UTF_8)
        val blocks = text.split("\r\n\r\n", limit = 2)
        val head = blocks.first().split("\r\n")
        if (head.isEmpty()) return null
        val parts = head[0].split(" ")
        if (parts.size < 2) return null
        val headers = mutableMapOf<String, String>()
        for (line in head.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > 1_000_000) error("body too large")
        val body = if (length <= 0) "" else {
            val buf = ByteArray(length)
            var off = 0
            while (off < length) {
                val n = input.read(buf, off, length - off)
                if (n < 0) break
                off += n
            }
            String(buf, 0, off, Charsets.UTF_8)
        }
        return Request(parts[0], parts[1], headers, body)
    }

    private fun readUntilHeaderEnd(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        while (out.size() < 65_536) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toByteArray()
            out.write(b)
            val n = out.size()
            if (n >= 4) {
                val raw = out.toByteArray()
                if (raw[n - 4] == '\r'.code.toByte() &&
                    raw[n - 3] == '\n'.code.toByte() &&
                    raw[n - 2] == '\r'.code.toByte() &&
                    raw[n - 1] == '\n'.code.toByte()
                ) {
                    return raw
                }
            }
        }
        error("header too large")
    }

    private fun writeResponse(
        socket: Socket,
        code: Int,
        type: String,
        body: String,
        extra: Map<String, String> = emptyMap()
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            202 -> "Accepted"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "OK"
        }
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            for ((key, value) in extra) append("$key: $value\r\n")
            append("\r\n")
        }
        val output = socket.getOutputStream()
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }
}
