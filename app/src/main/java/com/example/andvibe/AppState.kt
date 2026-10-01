package com.example.andvibe

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors

object AppState {
    enum class Tab { CONSOLE, FILES, VIBE }

    val io = Executors.newSingleThreadExecutor()
    val history = ArrayDeque<Pair<String, String>>()

    lateinit var reposDir: File
    @Volatile var cwd: File = File(".")
    @Volatile var openFile: File? = null
    var tab = Tab.CONSOLE
    var warnedPlain = false
    @Volatile var vibeBusy = false
    var vibeResult = ""
    var writtenPaths: List<String> = emptyList()

    private val logBuffer = StringBuilder()
    private var ready = false

    fun init(context: Context) {
        if (ready) return
        reposDir = File(context.applicationContext.filesDir, "repos").apply { mkdirs() }
        cwd = reposDir
        log("AndVibe")
        log("Type help")
        log("git clone https://github.com/user/repo")
        ready = true
    }

    fun text(): String = synchronized(logBuffer) { logBuffer.toString() }

    fun log(line: String) {
        val text = line.trimEnd()
        synchronized(logBuffer) {
            logBuffer.append(text).append('\n')
            if (logBuffer.length > 120_000) {
                logBuffer.delete(0, logBuffer.length - 80_000)
            }
        }
        UiBridge.updateLog()
    }

    fun clear() {
        synchronized(logBuffer) { logBuffer.setLength(0) }
        UiBridge.updateLog()
    }
}

object UiBridge {
    interface Listener {
        fun onLog()
        fun onFiles()
        fun onOpen(file: File)
        fun onPreview(file: File)
        fun onVibe()
    }

    var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())

    fun updateLog() {
        main.post { listener?.onLog() }
    }

    fun filesChanged() {
        main.post { listener?.onFiles() }
    }

    fun open(file: File) {
        main.post { listener?.onOpen(file) }
    }

    fun preview(file: File) {
        main.post { listener?.onPreview(file) }
    }

    fun vibeUpdate() {
        main.post { listener?.onVibe() }
    }
}
