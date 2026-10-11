package com.example.andvibe

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * HTTP/1.1 framing for DebugMcp's streamable-HTTP endpoint.
 * No sockets here, so it runs in JVM tests.
 */
internal object McpHttp {
    const val PATH = "/mcp"
    private const val MAX_HEADER = 65_536
    private const val MAX_BODY = 1_000_000

    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    class Response(
        val code: Int,
        val type: String,
        val body: String,
        val headers: Map<String, String> = emptyMap(),
    )

    /** Null when the client closed before sending a request line. Throws on oversized input. */
    fun read(input: InputStream): Request? {
        val header = readUntilHeaderEnd(input) ?: return null
        val text = header.toString(Charsets.UTF_8)
        val head = text.split("\r\n\r\n", limit = 2).first().split("\r\n")
        val parts = head[0].split(" ")
        if (parts.size < 2) return null
        val headers = mutableMapOf<String, String>()
        for (line in head.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY) error("body too large")
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

    /** Maps a request to a response; [dispatch] returns null for JSON-RPC notifications. */
    fun route(request: Request, dispatch: (String) -> String?): Response {
        if (request.path.substringBefore('?') != PATH) {
            return Response(404, "application/json", """{"error":"not found"}""")
        }
        if (request.method != "POST") {
            return Response(405, "application/json", """{"error":"use POST"}""")
        }
        val session = mapOf("Mcp-Session-Id" to "andvibe")
        val reply = dispatch(request.body)
            ?: return Response(202, "application/json", "", session)
        val sse = request.headers["accept"].orEmpty().contains("text/event-stream")
        return if (sse) {
            Response(
                200,
                "text/event-stream",
                "event: message\ndata: $reply\n\n",
                session + mapOf("Cache-Control" to "no-cache"),
            )
        } else {
            Response(200, "application/json", reply, session)
        }
    }

    fun encode(response: Response): ByteArray {
        val bytes = response.body.toByteArray(Charsets.UTF_8)
        val reason = when (response.code) {
            200 -> "OK"
            202 -> "Accepted"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "OK"
        }
        val head = buildString {
            append("HTTP/1.1 ${response.code} $reason\r\n")
            append("Content-Type: ${response.type}\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            for ((key, value) in response.headers) append("$key: $value\r\n")
            append("\r\n")
        }
        return head.toByteArray(Charsets.US_ASCII) + bytes
    }

    private fun readUntilHeaderEnd(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        var tail = 0
        while (out.size() < MAX_HEADER) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toByteArray()
            out.write(b)
            // Track the \r\n\r\n terminator without re-copying the buffer per byte.
            tail = when {
                b == '\r'.code && (tail == 0 || tail == 2) -> tail + 1
                b == '\n'.code && (tail == 1 || tail == 3) -> tail + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (tail == 4) return out.toByteArray()
        }
        error("header too large")
    }
}
