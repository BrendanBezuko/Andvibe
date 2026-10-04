package com.example.andvibe

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object AppState {
    enum class Tab { CONSOLE, BOARD, FILES, SEARCH, GIT, VIBE, BUILD }

    val io = Executors.newSingleThreadExecutor()
    val agentIo = Executors.newSingleThreadExecutor()
    val agentStop = AtomicBoolean(false)
    val agentSteps = mutableListOf<String>()
    val history = ArrayDeque<Pair<String, String>>()
    val chat = mutableListOf<Pair<String, String>>()

    lateinit var appContext: Context
    lateinit var reposDir: File
    @Volatile var cwd: File = File(".")
    @Volatile var openFile: File? = null
    var tab = Tab.CONSOLE
    var warnedPlain = false
    @Volatile var vibeBusy = false
    @Volatile var vibeRepo: File? = null
    var vibeResult = ""
    var writtenPaths: List<String> = emptyList()
    @Volatile var feedBusy = false
    @Volatile var findBusy = false
    var findHits: List<FossSearch.RepoHit> = emptyList()
    var findNews: List<FossSearch.NewsHit> = emptyList()
    var findBrief: String = ""
    var findNewsTab = false
    var findNote: String = ""
    @Volatile var downloadBusy = false
    @Volatile var downloadNote: String? = null

    @Volatile var buildBusy = false
    @Volatile var reviseBusy = false
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
        WorkspaceStore.init(appContext)
        ChatStore.init(appContext)
        reposDir = File(appContext.filesDir, "repos").apply { mkdirs() }
        WorkspaceStore.migrate()
        cwd = ProjectStore.restore(appContext, reposDir)?.takeIf { WorkspaceStore.contains(it) } ?: reposDir
        log("AndVibe")
        log("Type help")
        if (cwd.canonicalFile == reposDir.canonicalFile) {
            log("git clone https://github.com/user/repo")
        } else {
            log("opened ${RepoFiles.display(cwd, reposDir)}")
        }
        loadWorkspaceBuild()
        ready = true
    }

    fun loadWorkspaceBuild() {
        lastApk = ApkLibrary.list(appContext).firstOrNull()?.absolutePath
        val log = BuildHistory.latestLog()
        synchronized(buildBuffer) {
            buildBuffer.setLength(0)
            buildBuffer.append(log.takeLast(80_000))
        }
        UiBridge.buildUpdate()
    }

    fun workBusy(): Boolean = vibeBusy || buildBusy || reviseBusy

    fun fitWorkspace(toRoot: Boolean = false): Boolean {
        if (!toRoot && WorkspaceStore.contains(cwd)) return false
        if (cwd.canonicalFile == reposDir.canonicalFile) return false
        cwd = reposDir
        gitSnapshot = null
        gitDetail = null
        UiBridge.projectChanged()
        return true
    }

    fun inWorkspace(file: File): File {
        if (!WorkspaceStore.contains(file)) error("${RepoFiles.display(file, reposDir)} is not in this workspace")
        return file
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
        fun onUsage()
        fun onScreenshot(tab: AppState.Tab?, done: (Bitmap?) -> Unit)
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

    fun usageUpdate() {
        main.post { listener?.onUsage() }
    }

    // Blocks the caller, so never call it from the main thread.
    fun screenshot(tab: AppState.Tab?, timeoutMs: Long = 8_000): Bitmap? {
        val latch = CountDownLatch(1)
        val shot = AtomicReference<Bitmap?>(null)
        main.post {
            val target = listener
            if (target == null) {
                latch.countDown()
            } else {
                target.onScreenshot(tab) { bitmap ->
                    shot.set(bitmap)
                    latch.countDown()
                }
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) error("screenshot timed out")
        return shot.get()
    }
}
