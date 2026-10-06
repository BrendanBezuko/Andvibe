package com.example.andvibe

import com.example.andvibe.core.BoundedLog
import com.example.andvibe.core.GitOps
import com.example.andvibe.core.RepoFiles

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
    enum class Tab { CONSOLE, BOARD, FILES, SEARCH, GIT, VIBE, UNDERSTAND, BUILD }

    val io = Executors.newSingleThreadExecutor()
    val agentIo = Executors.newSingleThreadExecutor()
    val agentStop = AtomicBoolean(false)
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
    @Volatile var vibeBusy = false
    @Volatile var vibeRepo: File? = null
    var vibeResult = ""
    var writtenPaths: List<String> = emptyList()
    @Volatile var understandBusy = false
    @Volatile var understandRepo: File? = null
    val understandStop = AtomicBoolean(false)
    var understandText = ""
    var understandNote = ""
    @Volatile var feedBusy = false
    @Volatile var findBusy = false
    var findHits: List<FossSearch.RepoHit> = emptyList()
    var findNews: List<FossSearch.NewsHit> = emptyList()
    var findBrief: String = ""
    var findNewsTab = false
    var findNote: String = ""
    @Volatile var downloadBusy = false
    @Volatile var downloadNote: String? = null
    @Volatile var importBusy = false
    @Volatile var consoleBusy = false

    @Volatile var buildBusy = false
    @Volatile var reviseBusy = false
    var lastApk: String? = null

    @Volatile var gitBusy = false
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

    fun anyBusy(): Boolean =
        vibeBusy || buildBusy || reviseBusy || understandBusy ||
            gitBusy || findBusy || downloadBusy || importBusy || consoleBusy || feedBusy ||
            Jobs.active().isNotEmpty()

    fun busyLabel(): String? {
        Jobs.active().lastOrNull()?.let { return "${it.label}…" }
        return when {
            vibeBusy -> "Agent working…"
            buildBusy -> "Building…"
            reviseBusy -> "Revising…"
            understandBusy -> "Understanding…"
            gitBusy -> "Git working…"
            downloadBusy -> downloadNote ?: "Downloading…"
            importBusy -> "Importing…"
            findBusy -> "Searching…"
            consoleBusy -> "Running command…"
            feedBusy -> "Refreshing feed…"
            else -> null
        }
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
