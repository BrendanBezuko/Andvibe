package com.example.andvibe

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.example.andvibe.agent.AgentRuntime
import com.example.andvibe.agent.ToolRegistry
import com.example.andvibe.core.GitOps
import com.example.andvibe.core.RepoFiles
import com.example.andvibe.features.board.BoardFeature
import com.example.andvibe.features.build.BuildFeature
import com.example.andvibe.features.build.BuildLog
import com.example.andvibe.features.console.ConsoleFeature
import com.example.andvibe.features.console.ConsoleLog
import com.example.andvibe.features.files.FilesFeature
import com.example.andvibe.features.git.GitFeature
import com.example.andvibe.features.search.SearchFeature
import com.example.andvibe.features.settings.SettingsFeature
import com.example.andvibe.features.understand.UnderstandFeature
import com.example.andvibe.features.vibe.VibeFeature
import com.example.andvibe.features.workspace.WorkspaceFeature
import com.example.andvibe.shell.Shell
import com.example.andvibe.tasks.AppDispatchers
import com.example.andvibe.tasks.Res
import com.example.andvibe.tasks.TaskRunner
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Composition root, constructed once in AndVibeApp.onCreate.
 * Construction order is initialization order — the compiler enforces it.
 */
class AppGraph(app: Application) {
    val dispatchers = AppDispatchers()
    val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    val secrets = SecretStore(app)
    val shell = Shell()
    val session: ProjectSession
    val tasks: TaskRunner
    val consoleLog = ConsoleLog()
    val buildLog = BuildLog()
    val buildService: BuildService
    val toolRegistry: ToolRegistry
    val agentRuntime: AgentRuntime
    val searchFeature: SearchFeature
    val understandFeature: UnderstandFeature
    val buildFeature: BuildFeature
    val consoleFeature: ConsoleFeature
    val gitFeature: GitFeature
    val filesFeature: FilesFeature
    val boardFeature: BoardFeature
    val workspaceFeature: WorkspaceFeature
    val settingsFeature: SettingsFeature
    lateinit var vibeFeature: VibeFeature
    var ready: Boolean = false
        private set

    init {
        val main = Handler(Looper.getMainLooper())
        // Forward refs filled after gitFeature construction.
        var invalidateGit: () -> Unit = {}
        session = ProjectSession(
            app,
            onGitInvalidate = { invalidateGit() },
            onProjectChanged = { shell.projectChanged() },
        )
        tasks = TaskRunner(
            scope = scope,
            post = { main.post(it) },
            serviceSync = { WorkService.sync(app, it) },
            notifyDone = { task, done -> Notify.done(app, task, done.title, done.text, done.apk) },
            onChanged = { shell.busyChanged() },
        )
        Console.bridgeLog = consoleLog
        Console.shell = shell
        Console.session = session
        WorkspaceStore.init(app)
        WorkspaceStore.onUsageChanged = { shell.usageChanged() }
        BuildHistory.init(app)
        BuildHistory.onBuildChanged = { shell.buildChanged() }
        ChatStore.init(app)
        PromptStore.init(app)
        GitOps.auth = {
            GitOps.Auth(
                name = secrets.gitName().ifBlank { "AndVibe" },
                email = secrets.gitEmail().ifBlank { "andvibe@local" },
                user = secrets.gitUser(),
                token = secrets.gitToken(),
            )
        }
        WorkspaceStore.migrate()
        session.restoreLastProject()

        val logLine: (String) -> Unit = {
            consoleLog.append(it)
            shell.logChanged()
            DebugLog.step("console", it)
        }

        buildService = BuildService(app, tasks, onBuildChanged = { shell.buildChanged() })
        gitFeature = GitFeature(
            tasks = tasks,
            dispatchers = dispatchers,
            session = session,
            secrets = secrets,
            log = logLine,
            onFilesChanged = { shell.filesChanged() },
        )
        invalidateGit = { gitFeature.invalidate() }
        buildFeature = BuildFeature(
            app = app,
            tasks = tasks,
            dispatchers = dispatchers,
            session = session,
            buildService = buildService,
            buildLog = buildLog,
            consoleLog = logLine,
            vibeHistory = {
                if (::vibeFeature.isInitialized) vibeFeature.historyForAi() else ArrayDeque()
            },
            onVibeFilesChanged = { paths ->
                if (::vibeFeature.isInitialized) vibeFeature.applyExternalWrites(paths)
            },
            onGitRefresh = { gitFeature.refreshSnapshotIfIdle() },
            onBuildChanged = { shell.buildChanged() },
            onFilesChanged = { shell.filesChanged() },
        )
        toolRegistry = ToolRegistry(
            cloudBuild = { root ->
                val result = buildService.agentCloudBuild(
                    root,
                    secrets.buildUrl(),
                    secrets.buildToken(),
                    buildLog,
                )
                buildFeature.syncLogFromBuffer()
                buildFeature.setLastApk(ApkLibrary.list(app).firstOrNull()?.absolutePath)
                result
            },
            includeProject = { name -> WorkspaceStore.include(name) },
        )
        agentRuntime = AgentRuntime(
            tools = toolRegistry,
            launchAgent = { block ->
                tasks.launch(
                    label = "Agent working",
                    tab = Tab.VIBE,
                    holds = setOf(Res.AGENT),
                    on = dispatchers.agent,
                    track = true,
                    block = block,
                )
            },
        )
        vibeFeature = VibeFeature(
            app = app,
            agentRuntime = agentRuntime,
            tasks = tasks,
            session = session,
            onFilesChanged = { shell.filesChanged() },
            onGitUpdate = {},
            onProjectChanged = { shell.projectChanged() },
            log = logLine,
            gitBusy = { tasks.holds(Res.GIT) },
            refreshGitSnapshot = { gitFeature.refreshSnapshotIfIdle() },
        )
        vibeFeature.syncWorkspace()
        vibeFeature.bootstrapFromStore()
        filesFeature = FilesFeature(
            app = app,
            tasks = tasks,
            dispatchers = dispatchers,
            session = session,
            log = logLine,
            onGitInvalidate = { gitFeature.invalidate() },
            onFilesChanged = { shell.filesChanged() },
            onProjectChanged = { shell.projectChanged() },
            importFolder = { uri, repos, note ->
                FolderImport.importTree(app, uri, repos, note)
            },
        )
        searchFeature = SearchFeature(
            app = app,
            tasks = tasks,
            dispatchers = dispatchers,
            session = session,
            log = logLine,
            onGitInvalidate = { gitFeature.invalidate() },
            setPathBanner = { filesFeature.setPathBanner(it) },
            onProjectChanged = { shell.projectChanged() },
            onFilesChanged = { shell.filesChanged() },
        )
        understandFeature = UnderstandFeature(
            tasks = tasks,
            dispatchers = dispatchers,
            log = logLine,
        )
        consoleFeature = ConsoleFeature(
            tasks = tasks,
            dispatchers = dispatchers,
            log = consoleLog,
            context = {
                com.example.andvibe.features.console.ConsoleContext(
                    session = session,
                    log = consoleLog,
                    appContext = app,
                    onOpen = { shell.open(it) },
                    onPreview = { shell.preview(it) },
                    onFilesChanged = { shell.filesChanged() },
                    inWorkspace = { session.inWorkspace(it) },
                    projectRoot = { session.projectRoot() },
                    activeRepos = { WorkspaceStore.activeRepos() },
                    includeProject = { WorkspaceStore.include(it) },
                    rememberProject = { ProjectStore.remember(app, it) },
                    onGitRefresh = { gitFeature.refreshSnapshotIfIdle() },
                )
            },
            onLogChanged = { shell.logChanged() },
        )
        RequirementsStore.init(app)
        boardFeature = BoardFeature(tasks, dispatchers, session, logLine)
        workspaceFeature = WorkspaceFeature(tasks)
        settingsFeature = SettingsFeature(app, secrets, onMcpChanged = { shell.mcpChanged() })
        buildFeature.loadFromStores()

        logLine("AndVibe")
        logLine("Type help")
        if (session.cwd.canonicalFile == session.reposDir.canonicalFile) {
            logLine("git clone https://github.com/user/repo")
        } else {
            logLine("opened ${RepoFiles.display(session.cwd, session.reposDir)}")
        }
        ready = true
    }

    fun anyBusy(): Boolean = tasks.anyActive()

    fun busyLabel(): String? {
        val task = tasks.newest() ?: return null
        if (Res.DOWNLOAD in task.holds || Res.IMPORT in task.holds) {
            filesFeature.state.value.pathBanner?.let { return it }
        }
        return "${task.label}…"
    }

    fun workBusy(): Boolean =
        tasks.holds(Res.AGENT) || tasks.holds(Res.BUILD) ||
            tasks.holds(Res.REVISE) || tasks.holds(Res.UNDERSTAND) ||
            tasks.holds(Res.BOARD)
}
