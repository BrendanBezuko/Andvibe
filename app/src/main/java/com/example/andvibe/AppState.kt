package com.example.andvibe

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors

object AppState {
    enum class Tab { CONSOLE, FILES, GIT, VIBE, BUILD }

    val io = Executors.newSingleThreadExecutor()
    val history = ArrayDeque<Pair<String, String>>()

    lateinit var appContext: Context
    lateinit var reposDir: File
    @Volatile var cwd: File = File(".")
    @Volatile var openFile: File? = null
    var tab = Tab.CONSOLE
    var warnedPlain = false
    @Volatile var vibeBusy = false
    var vibeResult = ""
    var writtenPaths: List<String> = emptyList()

    @Volatile var buildBusy = false
    var lastApk: String? = null

    @Volatile var gitBusy = false
    var gitSnapshot: GitOps.Snapshot? = null
    var gitDetail: String? = null
    var gitMessageClear = false

    private val logBuffer = StringBuilder()
    private val buildBuffer = StringBuilder()
    private var ready = false

    fun isReady(): Boolean = ready

    fun init(context: Context) {
        if (ready) return
        appContext = context.applicationContext
        reposDir = File(appContext.filesDir, "repos").apply { mkdirs() }
        cwd = ProjectStore.restore(appContext, reposDir) ?: reposDir
        log("AndVibe")
        log("Type help")
        if (cwd.canonicalFile == reposDir.canonicalFile) {
            log("git clone https://github.com/user/repo")
        } else {
            log("opened ${RepoFiles.display(cwd, reposDir)}")
        }
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
        DebugLog.step("console", text)
    }

    fun clear() {
        synchronized(logBuffer) { logBuffer.setLength(0) }
        DebugLog.step("console", "cleared")
        UiBridge.updateLog()
    }

    fun buildText(): String = synchronized(buildBuffer) { buildBuffer.toString() }

    fun clearBuild() {
        synchronized(buildBuffer) { buildBuffer.setLength(0) }
        DebugLog.step("build", "cleared")
        UiBridge.buildUpdate()
    }

    fun buildLog(line: String) {
        val text = line.trimEnd()
        synchronized(buildBuffer) {
            buildBuffer.append(text).append('\n')
            if (buildBuffer.length > 120_000) {
                buildBuffer.delete(0, buildBuffer.length - 80_000)
            }
        }
        UiBridge.buildUpdate()
        DebugLog.step("build", text)
    }
}

object UiBridge {
    interface Listener {
        fun onLog()
        fun onFiles()
        fun onOpen(file: File)
        fun onPreview(file: File)
        fun onVibe()
        fun onBuild()
        fun onGit()
        fun onProject()
        fun onMcp()
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

    fun buildUpdate() {
        main.post { listener?.onBuild() }
    }

    fun gitUpdate() {
        main.post { listener?.onGit() }
    }

    fun projectChanged() {
        main.post { listener?.onProject() }
    }

    fun mcpUpdate() {
        main.post { listener?.onMcp() }
    }
}
