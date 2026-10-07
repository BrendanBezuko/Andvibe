package com.example.andvibe.ui

import android.content.Intent
import android.net.Uri
import android.util.TypedValue
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import com.example.andvibe.ApkLibrary
import com.example.andvibe.AppGraph
import com.example.andvibe.BuildHistory
import com.example.andvibe.R
import com.example.andvibe.RequirementsStore
import com.example.andvibe.SecretStore
import com.example.andvibe.Tab
import com.example.andvibe.databinding.ActivityMainBinding
import com.example.andvibe.features.board.BoardFeature
import com.example.andvibe.features.build.BuildFeature
import com.example.andvibe.features.git.GitFeature
import com.example.andvibe.features.search.SearchFeature
import com.example.andvibe.features.understand.UnderstandFeature
import java.io.File

/**
 * Constructs and starts page controllers for [MainActivity]. Keeps the Activity as a thin shell.
 */
class MainControllers(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val graph: AppGraph,
    private val store: SecretStore,
    private val openFolder: ActivityResultLauncher<Intent>,
    private val currentTab: () -> Tab,
    private val showTab: (Tab) -> Unit,
    private val editing: () -> Boolean,
    private val paintBusy: () -> Unit,
    private val paintTape: () -> Unit,
    private val paintProject: () -> Unit,
    private val onUsage: () -> Unit,
    private val syncBack: () -> Unit,
    private val logLine: (String) -> Unit,
    private val ensureTrackedBackground: () -> Unit,
    private val requestBackground: () -> Unit,
    private val batteryExempt: () -> Boolean,
    private val requestMicPermission: (onResult: (Boolean) -> Unit) -> Unit,
) {
    lateinit var console: ConsolePageController
        private set
    lateinit var files: FilesPageController
        private set
    lateinit var search: SearchPageController
        private set
    lateinit var understand: UnderstandPageController
        private set
    lateinit var build: BuildPageController
        private set
    lateinit var git: GitPageController
        private set
    lateinit var vibe: VibePageController
        private set
    lateinit var board: BoardPageController
        private set
    lateinit var workspace: WorkspacePageController
        private set
    lateinit var settings: SettingsPageController
        private set

    fun startAll() {
        setupSettings()
        setupFiles()
        setupVibe()
        setupConsole()
        setupSearch()
        setupGit()
        setupUnderstand()
        setupBuild()
        setupBoard()
        setupWorkspace()
    }

    fun switchedWorkspace() {
        if (editing()) files.closeEditor(save = true)
        board.stopSpeech()
        workspace.fitAfterSwitch()
        graph.boardFeature.refresh()
        if (::board.isInitialized) board.renderBoard()
        graph.buildFeature.loadFromStores()
        build.clearShownApks()
        workspace.renderWorkspace()
        onUsage()
        files.refreshFileList()
        graph.vibeFeature.markProjectChanged()
        build.onExternalUpdate()
    }

    fun ensureProject(root: File) {
        val open = runCatching { graph.session.projectRoot() }.getOrNull()
        if (open?.canonicalFile != root.canonicalFile) files.openProject(root)
    }

    fun startVibeFromBoard(instruction: String) {
        showTab(Tab.VIBE)
        vibe.selectChatTab()
        vibe.sendInstruction(instruction)
    }

    private fun setupSettings() {
        settings = SettingsPageController(
            activity = activity,
            page = binding.settingsPage,
            feature = graph.settingsFeature,
            secrets = store,
            session = graph.session,
            openSettingsButton = binding.consolePage.openSettings,
            color = { activity.getColor(it) },
            dp = { dp(it) },
            requestBackground = requestBackground,
            batteryExempt = batteryExempt,
            closeWorkspaceIfOpen = { if (::workspace.isInitialized) workspace.close() },
            syncBack = syncBack,
            log = logLine,
            currentTab = currentTab,
            refreshGitIfVisible = { graph.gitFeature.refresh() },
            syncVaultKey = { provider, key ->
                if (::console.isInitialized) console.setVaultKey(provider, key)
            },
        )
        settings.start()
    }

    private fun setupWorkspace() {
        workspace = WorkspacePageController(
            activity = activity,
            page = binding.workspacePage,
            feature = graph.workspaceFeature,
            lifecycleOwner = activity,
            session = graph.session,
            inflate = activity.layoutInflater,
            openWorkspaceBox = binding.openWorkspaceBox,
            color = { activity.getColor(it) },
            selectableBackground = { selectableBackground() },
            closeSettingsIfOpen = { if (::settings.isInitialized) settings.close() },
            syncBack = syncBack,
            onUsage = onUsage,
            onSwitched = { switchedWorkspace() },
            refreshFileList = { if (::files.isInitialized) files.refreshFileList() },
            markVibeProjectChanged = { graph.vibeFeature.markProjectChanged() },
            forgetWorkspaceExtras = { id ->
                graph.vibeFeature.forgetWorkspace(id)
                ApkLibrary.forget(activity, id)
                BuildHistory.forget(id)
                RequirementsStore.forget(id)
            },
        )
        workspace.start()
        binding.openProjectBox.setOnClickListener { files.showProjects() }
    }

    private fun setupBoard() {
        board = BoardPageController(
            activity = activity,
            page = binding.boardPage,
            feature = graph.boardFeature,
            inflate = activity.layoutInflater,
            startVibeFromBoard = { startVibeFromBoard(it) },
            providerCreds = {
                val provider = vibe.currentProvider()
                BoardFeature.Creds(
                    provider,
                    store.get(provider, "key", ""),
                    store.get(provider, "model", provider.defaultModel),
                    store.get(provider, "base", provider.defaultBase),
                )
            },
            saveProvider = { vibe.saveCurrentProvider() },
            requestMicPermission = requestMicPermission,
        )
        board.start()
    }

    private fun setupConsole() {
        console = ConsolePageController(
            page = binding.consolePage,
            feature = graph.consoleFeature,
            log = graph.consoleLog,
            session = graph.session,
            store = store,
            lifecycleOwner = activity,
            scope = graph.scope,
            repoDispatcher = graph.dispatchers.repo,
            color = { activity.getColor(it) },
            dp = { dp(it) },
            currentProvider = { vibe.currentProvider() },
            saveProvider = { vibe.saveCurrentProvider() },
            onSecretsSynced = { gitToken, ssh ->
                if (::settings.isInitialized) {
                    settings.setGitFields(token = gitToken, ssh = ssh)
                } else {
                    binding.settingsPage.gitHttpsToken.setText(gitToken)
                    binding.settingsPage.gitSsh.setText(ssh)
                }
            },
            onVariablesSynced = { _, model, base, gitName, gitEmail, gitUser, origin ->
                binding.vibePage.model.setText(model)
                binding.vibePage.baseUrl.setText(base)
                if (::settings.isInitialized) {
                    settings.setGitFields(
                        name = gitName,
                        email = gitEmail,
                        user = gitUser,
                        origin = origin,
                    )
                } else {
                    binding.settingsPage.gitName.setText(gitName)
                    binding.settingsPage.gitEmail.setText(gitEmail)
                    binding.settingsPage.gitHttpsUser.setText(gitUser)
                    binding.settingsPage.gitOrigin.setText(origin)
                }
            },
            syncApiKeyField = { provider, key ->
                if (::settings.isInitialized) settings.setApiKeyField(provider, key)
            },
            paintBusy = paintBusy,
            openUrl = { url ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            },
            runOnUi = { block -> activity.runOnUiThread(block) },
        )
        console.start()
    }

    private fun setupSearch() {
        search = SearchPageController(
            page = binding.searchPage,
            feature = graph.searchFeature,
            lifecycleOwner = activity,
            inflate = activity.layoutInflater,
            color = { activity.getColor(it) },
            dp = { dp(it) },
            currentProvider = { vibe.currentProvider() },
            providerModel = { store.get(it, "model", it.defaultModel) },
            saveProvider = { vibe.saveCurrentProvider() },
            providerCreds = { provider ->
                SearchFeature.Creds(
                    provider,
                    store.get(provider, "key", ""),
                    store.get(provider, "model", provider.defaultModel),
                    store.get(provider, "base", provider.defaultBase),
                )
            },
            onGoToFiles = {
                files.saveEditor(announce = false)
                binding.bottomNav.selectedItemId = R.id.nav_files
                files.refreshFileList()
                paintBusy()
            },
            onCloneError = { message -> files.setCloneErrorPath(message) },
            closeEditorIfNeeded = {
                if (editing()) files.closeEditor(save = true)
            },
        )
        search.start()
    }

    private fun setupFiles() {
        files = FilesPageController(
            page = binding.filesPage,
            feature = graph.filesFeature,
            session = graph.session,
            lifecycleOwner = activity,
            inflate = activity.layoutInflater,
            density = activity.resources.displayMetrics.density,
            launchDocumentTree = {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                openFolder.launch(intent)
            },
            onOpenWorkspace = { if (::workspace.isInitialized) workspace.open() },
            log = logLine,
            paintBusy = paintBusy,
            paintTape = paintTape,
            paintProject = paintProject,
            syncBack = syncBack,
            onAfterOpenProject = {
                if (::workspace.isInitialized && workspace.isOpen) workspace.renderRepoChecks()
            },
            refreshGitIfVisible = {
                if (currentTab() == Tab.GIT) graph.gitFeature.refresh()
            },
            markVibeProjectChanged = { graph.vibeFeature.markProjectChanged() },
        )
        files.start()
    }

    private fun setupVibe() {
        vibe = VibePageController(
            activity = activity,
            page = binding.vibePage,
            feature = graph.vibeFeature,
            store = store,
            inflate = activity.layoutInflater,
            color = { activity.getColor(it) },
            dp = { dp(it) },
            screenWidth = { activity.resources.displayMetrics.widthPixels },
            vibeRoot = { graph.session.selectedRoot() },
            agentStopping = { graph.agentRuntime.isStopping() },
            beforeSend = {
                files.saveEditor(announce = false)
                ensureTrackedBackground()
                showTab(Tab.VIBE)
                vibe.selectChatTab()
            },
            ensureProject = { ensureProject(it) },
            openWorkspace = { workspace.open() },
            openProject = { files.openProject(it) },
            sessionCwd = { graph.session.cwd },
            sessionOpenFile = { graph.session.openFile },
            reloadOpenFiles = { files.reloadOpen(it) },
            refreshFileListIfNeeded = { if (!editing()) files.refreshFileList() },
            paintBusy = paintBusy,
            log = logLine,
            onProviderChanged = { },
        )
        vibe.start()
    }

    private fun setupUnderstand() {
        understand = UnderstandPageController(
            activity = activity,
            page = binding.understandPage,
            feature = graph.understandFeature,
            color = { activity.getColor(it) },
            cssColor = { cssColor(it) },
            vibeRoot = { graph.session.selectedRoot() },
            loadMermaid = {
                runCatching {
                    activity.assets.open("understand/mermaid.min.js").use { it.readBytes() }
                }.getOrNull()
            },
            beforeTrackedRun = {
                files.saveEditor(announce = false)
                ensureTrackedBackground()
            },
            ensureProject = { ensureProject(it) },
            openProject = { files.openProject(it) },
            openWorkspace = { workspace.open() },
            saveProvider = { vibe.saveCurrentProvider() },
            providerCreds = {
                val provider = vibe.currentProvider()
                UnderstandFeature.Creds(
                    provider,
                    store.get(provider, "key", ""),
                    store.get(provider, "model", provider.defaultModel).ifBlank {
                        binding.vibePage.model.text?.toString()?.trim().orEmpty().ifBlank { provider.defaultModel }
                    },
                    store.get(provider, "base", provider.defaultBase).ifBlank {
                        binding.vibePage.baseUrl.text?.toString()?.trim().orEmpty().ifBlank { provider.defaultBase }
                    },
                )
            },
            onSaved = {
                if (!editing()) files.refreshFileList()
                graph.gitFeature.refreshSnapshotIfIdle()
                graph.shell.filesChanged()
            },
            paintBusy = paintBusy,
        )
        understand.start()
    }

    private fun setupGit() {
        git = GitPageController(
            activity = activity,
            page = binding.gitPage,
            feature = graph.gitFeature,
            session = graph.session,
            scope = graph.scope,
            dispatchers = graph.dispatchers,
            providerCreds = { provider ->
                GitFeature.Creds(
                    provider,
                    store.get(provider, "key", ""),
                    store.get(provider, "model", provider.defaultModel),
                    store.get(provider, "base", provider.defaultBase),
                )
            },
            saveProvider = { vibe.saveCurrentProvider() },
            currentProvider = { vibe.currentProvider() },
            saveEditor = { files.saveEditor(announce = false) },
            openFilesTab = { binding.bottomNav.selectedItemId = R.id.nav_files },
            refreshFileList = { files.refreshFileList() },
            openEditor = { files.openEditor(it) },
            log = logLine,
            syncBack = syncBack,
            paintBusy = paintBusy,
        )
        git.start()
    }

    private fun setupBuild() {
        build = BuildPageController(
            activity = activity,
            page = binding.buildPage,
            feature = graph.buildFeature,
            buildUrl = { store.buildUrl() },
            buildToken = { store.buildToken() },
            providerCreds = { provider ->
                BuildFeature.Creds(
                    provider,
                    store.get(provider, "key", ""),
                    store.get(provider, "model", provider.defaultModel),
                    store.get(provider, "base", provider.defaultBase),
                )
            },
            currentProvider = { vibe.currentProvider() },
            saveProvider = { vibe.saveCurrentProvider() },
            selectedRoot = { graph.session.selectedRoot() },
            reposDir = { graph.session.reposDir },
            onBeforeBuildAction = { files.saveEditor(announce = false) },
            paintBusy = paintBusy,
            color = { activity.getColor(it) },
        )
        build.start()
    }

    private fun selectableBackground(): Int {
        val value = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return value.resourceId
    }

    private fun cssColor(resId: Int): String {
        val rgb = activity.getColor(resId) and 0xFFFFFF
        return "#%06X".format(rgb)
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
