package com.example.andvibe

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.example.andvibe.tasks.Res
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object DebugMcp {
    const val PORT = 8765
    private const val PREF = "andvibe_mcp"
    private val started = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val lock = Any()

    @Volatile
    private var enabled = true

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var listening = false

    @Volatile
    private var failure: String? = null

    @Volatile
    private var lastRequestAt = 0L

    @Volatile
    private var appContext: Context? = null

    fun statusText(): String {
        if (!enabled) return "Off"
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

    fun init(context: Context) {
        appContext = context.applicationContext
        enabled = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("enabled", true)
        if (enabled) start() else publish()
    }

    fun isEnabled(): Boolean = enabled

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
        enabled = on
        if (on) start() else stop()
    }

    fun start() {
        if (!enabled) return
        if (!started.compareAndSet(false, true)) return
        val gen = generation.get()
        Thread({ serve(gen) }, "andvibe-mcp").apply { isDaemon = true }.start()
    }

    fun stop() {
        val old = synchronized(lock) {
            generation.incrementAndGet()
            started.set(false)
            listening = false
            failure = null
            server.also { server = null }
        }
        try {
            old?.close()
        } catch (_: Exception) {
        }
        DebugLog.step("mcp", "stopped")
        publish()
    }

    private fun serve(gen: Int) {
        try {
            val socket = ServerSocket(PORT, 20, InetAddress.getByName("127.0.0.1"))
            synchronized(lock) {
                if (generation.get() != gen) {
                    socket.close()
                    return
                }
                server = socket
                listening = true
                failure = null
            }
            DebugLog.step("mcp", "listening on 127.0.0.1:$PORT")
            publish()
            while (true) {
                val conn = socket.accept()
                Thread({ handle(conn) }, "andvibe-mcp-conn").apply { isDaemon = true }.start()
            }
        } catch (t: Throwable) {
            synchronized(lock) {
                // stop() closed the socket on purpose; it already reset the state.
                if (generation.get() != gen) return
                server = null
                listening = false
                failure = t.message ?: t.javaClass.simpleName
                started.set(false)
            }
            DebugLog.step("mcp", "listen failed: ${failure}")
            publish()
        }
    }

    private fun publish() {
        graph()?.shell?.mcpChanged()
    }

    private fun graph(): AppGraph? =
        (appContext?.applicationContext as? AndVibeApp)?.graph

    private val rpc = McpRpc(::callTool)

    private fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        try {
            val request = McpHttp.read(socket.getInputStream()) ?: return
            lastRequestAt = System.currentTimeMillis()
            publish()
            val response = McpHttp.route(request, rpc::dispatch)
            socket.getOutputStream().apply {
                write(McpHttp.encode(response))
                flush()
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

    private fun callTool(name: String, args: JSONObject): JSONObject = when (name) {
        "screenshot" -> screenshot(args.optString("tab", ""), args.optString("name", ""))
        "logs" -> McpRpc.ok(DebugLog.text(args.optString("area", ""), args.optInt("limit", 200)))
        "build_log" -> McpRpc.ok(graph()?.buildLog?.snapshot().orEmpty().ifBlank { "build log is empty" })
        "console_log" -> McpRpc.ok(graph()?.consoleLog?.text().orEmpty().ifBlank { "console log is empty" })
        "state" -> McpRpc.ok(stateText())
        else -> McpRpc.error("unknown tool: $name")
    }

    private fun screenshot(tabArg: String, nameArg: String): JSONObject {
        val g = graph() ?: return McpRpc.error("AndVibe is not ready")
        val tab = tabArg.trim().takeIf { it.isNotEmpty() }?.let { raw ->
            Tab.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: return McpRpc.error("unknown tab: $raw")
        }
        val bitmap = g.shell.screenshot(tab)
            ?: return McpRpc.error("AndVibe is not on screen. Open it on the device and try again.")
        val png = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)
        val bytes = png.toByteArray()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        // Without a tab argument we don't know which page is showing, so don't claim one.
        val label = tab?.name?.lowercase() ?: "screen"
        val base = McpRpc.screenshotName(nameArg, label, stamp)
        val ctx = appContext ?: return McpRpc.error("AndVibe is not ready")
        val dir = ctx.getExternalFilesDir("screenshots") ?: error("external files dir is unavailable")
        dir.mkdirs()
        val file = File(dir, "$base.png")
        file.writeBytes(bytes)
        DebugLog.step("mcp", "screenshot ${file.name} ${bitmap.width}x${bitmap.height}")
        val note = buildString {
            append("saved ${file.absolutePath}\n")
            append("size ${bitmap.width}x${bitmap.height}, ${bytes.size} bytes\n")
            append("adb pull ${file.absolutePath} ../Andvibe-subscription/web/src/assets/screens/${file.name}")
        }
        val image = JSONObject()
            .put("type", "image")
            .put("mimeType", "image/png")
            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        return JSONObject()
            .put("content", JSONArray().put(image).put(JSONObject().put("type", "text").put("text", note)))
            .put("isError", false)
    }

    private fun stateText(): String {
        val g = graph()
        if (g == null || !g.ready) return "ready=false\nmcp=127.0.0.1:$PORT\nmcpListening=$listening"
        val session = g.session
        val tasks = g.tasks
        val snap = g.gitFeature.state.value.snapshot
        return buildString {
            append("ready=true\n")
            append("mcp=127.0.0.1:$PORT\n")
            append("mcpListening=$listening\n")
            append("cwd=${session.cwd.absolutePath}\n")
            append("open=${session.openFile?.absolutePath.orEmpty()}\n")
            append("buildBusy=${tasks.holds(Res.BUILD)}\n")
            append("reviseBusy=${tasks.holds(Res.REVISE)}\n")
            append("gitBusy=${tasks.holds(Res.GIT)}\n")
            append("vibeBusy=${tasks.holds(Res.AGENT)}\n")
            append("understandBusy=${tasks.holds(Res.UNDERSTAND)}\n")
            append("findBusy=${tasks.holds(Res.FIND)}\n")
            append("downloadBusy=${tasks.holds(Res.DOWNLOAD)}\n")
            append("importBusy=${tasks.holds(Res.IMPORT)}\n")
            append("consoleBusy=${tasks.holds(Res.CONSOLE)}\n")
            append("boardBusy=${tasks.holds(Res.BOARD)}\n")
            append("anyBusy=${g.anyBusy()}\n")
            append("busyLabel=${g.busyLabel().orEmpty()}\n")
            append("lastApk=${g.buildFeature.state.value.lastApkPath.orEmpty()}\n")
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
}
