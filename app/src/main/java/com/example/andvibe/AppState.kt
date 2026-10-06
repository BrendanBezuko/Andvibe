package com.example.andvibe

import com.example.andvibe.core.BoundedLog
import com.example.andvibe.tasks.Res
import com.example.andvibe.tasks.TaskRunner
import com.example.andvibe.core.GitOps
import com.example.andvibe.core.RepoFiles

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object AppState {
    enum class Tab { CONSOLE, BOARD, FILES, SEARCH, GIT, VIBE, UNDERSTAND, BUILD }

    val agentSteps = mutableListOf<String>()
    val history = ArrayDeque<Pair<String, String>>()
    val chat = mutableListOf<Pair<String, String>>()

    lateinit var appContext: Context
    lateinit var session: ProjectSession
    val reposDir: File get() = session.reposDir
    var cwd: File
        get() = session.cwd
        set(value) { session.cwd = value }
    var openFile: File?
        get() = session.openFile
        set(value) { session.openFile = value }
    var tab = Tab.CONSOLE
    var warnedPlain = false

    lateinit var tasks: TaskRunner

    val vibeBusy: Boolean get() = tasks.holds(Res.AGENT)
    @Volatile var vibeRepo: File? = null
    var vibeResult = ""
    var writtenPaths: List<String> = emptyList()
    val understandBusy: Boolean get() = tasks.holds(Res.UNDERSTAND)
    @Volatile var understandRepo: File? = null
    val understandStop = AtomicBoolean(false)
    var understandText = ""
    var understandNote = ""
    val feedBusy: Boolean get() = tasks.holds(Res.FEED)
    val findBusy: Boolean get() = tasks.holds(Res.FIND)
    var findHits: List<FossSearch.RepoHit> = emptyList()
    var findNews: List<FossSearch.NewsHit> = emptyList()
    var findBrief: String = ""
    var findNewsTab = false
    var findNote: String = ""
    val downloadBusy: Boolean get() = tasks.holds(Res.DOWNLOAD)
    @Volatile var downloadNote: String? = null
    val importBusy: Boolean get() = tasks.holds(Res.IMPORT)
    val consoleBusy: Boolean get() = tasks.holds(Res.CONSOLE)

    val buildBusy: Boolean get() = tasks.holds(Res.BUILD)
    val reviseBusy: Boolean get() = tasks.holds(Res.REVISE)
    var lastApk: String? = null

    val gitBusy: Boolean get() = tasks.holds(Res.GIT)
    var gitSnapshot: GitOps.Snapshot? = null
    var gitDetail: String? = null
    var gitMessageClear = false

    private val logBuffer = BoundedLog()
    private val buildBuffer = BoundedLog()
    private var ready = false

    fun isReady(): Boolean = ready

    fun init(context: Context, projectSession: ProjectSession) {
        if (ready) return
        appContext = context.applicationContext
        session = projectSession
        WorkspaceStore.migrate()
        session.restoreLastProject()
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
        buildBuffer.replace(BuildHistory.latestLog())
        UiBridge.buildUpdate()
    }

    fun workBusy(): Boolean = vibeBusy || buildBusy || reviseBusy || understandBusy

    fun anyBusy(): Boolean = tasks.anyActive()

    fun busyLabel(): String? {
        val task = tasks.newest() ?: return null
        if (Res.DOWNLOAD in task.holds || Res.IMPORT in task.holds) {
            downloadNote?.let { return it }
        }
        return "${task.label}…"
    }

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

    fun text(): String = logBuffer.text()

    fun log(line: String) {
        val text = logBuffer.append(line)
        UiBridge.updateLog()
        DebugLog.step("console", text)
    }

    fun clear() {
        logBuffer.clear()
        DebugLog.step("console", "cleared")
        UiBridge.updateLog()
    }

    fun buildText(): String = buildBuffer.text()

    fun clearBuild() {
        buildBuffer.clear()
        DebugLog.step("build", "cleared")
        UiBridge.buildUpdate()
    }

    fun buildLog(line: String) {
        val text = buildBuffer.append(line)
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
        fun onUnderstand()
        fun onBuild()
        fun onGit()
        fun onProject()
        fun onMcp()
        fun onUsage()
        fun onBusy()
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

    fun busyUpdate() {
        main.post { listener?.onBusy() }
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

    fun understandUpdate() {
        main.post { listener?.onUnderstand() }
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
