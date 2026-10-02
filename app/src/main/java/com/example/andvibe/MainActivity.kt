package com.example.andvibe

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.andvibe.databinding.ActivityMainBinding
import com.example.andvibe.databinding.RowCardBinding
import com.example.andvibe.databinding.RowGitFileBinding
import com.google.android.material.checkbox.MaterialCheckBox
import java.io.File

class MainActivity : AppCompatActivity(), UiBridge.Listener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SecretStore
    private var currentProvider = Provider.OPENAI
    private var spinnerReady = false
    private var savedText = ""
    private var editing = false
    private var displayed = emptyList<File>()
    private var settingsOpen = false
    private var boardOpen = false
    private var workspaceOpen = false

    private val openFolder = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        val raw = FolderImport.displayName(this, uri)
        val safe = runCatching { GitClient.safeRepoName(raw) }.getOrDefault("project")
        val existing = File(AppState.reposDir, safe)
        if (existing.isDirectory && !existing.list().isNullOrEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(safe)
                .setMessage("This name is already in AndVibe. Open the saved project, or import another copy.")
                .setPositiveButton("Open it") { _, _ -> openProject(existing) }
                .setNeutralButton("Import copy") { _, _ -> startImport(uri) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            startImport(uri)
        }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (settingsOpen) {
                closeSettings()
                return
            }
            if (boardOpen) {
                closeBoard()
                return
            }
            if (workspaceOpen) {
                closeWorkspace()
                return
            }
            if (AppState.tab == AppState.Tab.GIT && AppState.gitDetail != null) {
                closeGitDetail()
                return
            }
            if (editing) closeEditor(save = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(this)
        store = SecretStore(this)
        GitOps.authorName = store.gitName().ifBlank { "AndVibe" }
        GitOps.authorEmail = store.gitEmail().ifBlank { "andvibe@local" }
        GitOps.remoteUser = store.gitUser()
        GitOps.remoteToken = store.gitToken()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UiBridge.listener = this
        onBackPressedDispatcher.addCallback(this, backCallback)
        if (store.plain && !AppState.warnedPlain) {
            AppState.warnedPlain = true
            AppState.log("API keys could not be encrypted on this device. They stay in app-private storage.")
        }
        setupConsole()
        setupSettings()
        setupFiles()
        setupGit()
        setupVibe()
        setupBuild()
        setupNav()
        setupUsage()
        noteRepo()
        val open = AppState.openFile
        if (open != null && open.isFile) openEditor(open) else refreshFileList()
        onLog()
        onVibe()
        onBuild()
        onGit()
    }

    override fun onPause() {
        saveEditor(announce = false)
        if (::store.isInitialized) {
            saveProvider(currentProvider)
            saveBuildServer()
            saveWorkspaceName()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (UiBridge.listener === this) UiBridge.listener = null
        super.onDestroy()
    }

    override fun onLog() {
        val scroll = binding.consolePage.logScroll
        val child = scroll.getChildAt(0)
        val nearBottom = child == null || child.bottom <= scroll.height + scroll.scrollY + 160
        binding.consolePage.logView.text = AppState.text()
        if (nearBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onFiles() {
        if (!editing) {
            refreshFileList()
            return
        }
        val file = AppState.openFile
        if (file == null || !file.isFile) {
            editing = false
            AppState.openFile = null
            savedText = ""
            binding.filesPage.editor.setText("")
            refreshFileList()
            return
        }
        if (binding.filesPage.editor.text?.toString() != savedText) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        if (text != savedText) {
            savedText = text
            binding.filesPage.editor.setText(text)
        }
    }

    override fun onOpen(file: File) {
        openEditor(file)
        binding.bottomNav.selectedItemId = R.id.nav_files
    }

    override fun onPreview(file: File) {
        startActivity(
            Intent(this, PreviewActivity::class.java)
                .putExtra(PreviewActivity.EXTRA_PATH, file.absolutePath)
        )
    }

    override fun onVibe() {
        binding.vibePage.vibeSend.isEnabled = !AppState.vibeBusy
        if (AppState.vibeBusy) {
            binding.vibePage.vibeResult.text = "Working…"
            return
        }
        if (AppState.vibeResult.isNotEmpty()) {
            binding.vibePage.vibeResult.text = AppState.vibeResult
        }
        val paths = AppState.writtenPaths
        AppState.writtenPaths = emptyList()
        if (paths.isNotEmpty()) {
            reloadOpen(paths)
            if (!editing) refreshFileList()
        }
    }

    override fun onBuild() {
        val scroll = binding.buildPage.buildScroll
        val child = scroll.getChildAt(0)
        val nearBottom = child == null || child.bottom <= scroll.height + scroll.scrollY + 160
        val text = AppState.buildText()
        binding.buildPage.buildLog.text = text.ifBlank {
            "Paste the Cloud Run URL and token. Build logs stream here. Revise edits the project on this phone. The API key stays on the device."
        }
        val root = runCatching { AppState.projectRoot() }.getOrNull()
        binding.buildPage.projectLine.text = if (root == null) {
            "No repo yet. Clone one in Console."
        } else {
            "${RepoFiles.display(root, AppState.reposDir)} — ${JsRunner.detect(root)}"
        }
        binding.buildPage.apkPath.text = AppState.lastApk ?: "No APK yet"
        val busy = AppState.buildBusy || AppState.reviseBusy
        val apkReady = AppState.lastApk?.let { File(it).isFile } == true
        binding.buildPage.installApk.isEnabled = apkReady && !busy
        binding.buildPage.buildApk.isEnabled = !busy
        binding.buildPage.buildApk.text = if (AppState.buildBusy) "Building…" else "Build APK"
        binding.buildPage.reviseBuild.isEnabled = !busy && text.isNotBlank()
        binding.buildPage.reviseBuild.text = if (AppState.reviseBusy) "Revising…" else "Revise"
        if (nearBottom && text.isNotBlank()) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onGit() {
        val page = binding.gitPage
        val snap = AppState.gitSnapshot
        val showDetail = AppState.gitDetail != null
        page.gitBranch.text = when {
            AppState.gitBusy -> "Working…"
            snap == null || snap.branch.isBlank() -> "Git"
            else -> snap.branch
        }
        page.gitSummary.text = when {
            AppState.gitBusy -> "Working…"
            snap == null -> "Open a project, then refresh."
            else -> snap.summary
        }
        if (AppState.gitMessageClear) {
            page.gitMessage.setText("")
            AppState.gitMessageClear = false
        }
        page.gitDetail.text = AppState.gitDetail.orEmpty()
        page.gitDetailScroll.visibility = if (showDetail) View.VISIBLE else View.GONE
        page.gitDetailClose.visibility = if (showDetail) View.VISIBLE else View.GONE
        page.gitComposer.visibility = if (showDetail) View.GONE else View.VISIBLE
        val showLists = !showDetail && snap?.isRepo == true
        page.gitScroll.visibility = if (showLists) View.VISIBLE else View.GONE
        page.gitEmpty.text = when {
            snap == null -> "Open a project from Files."
            !snap.isRepo -> snap.summary
            else -> "No changes"
        }
        page.gitEmpty.visibility = if (!showDetail && !showLists) View.VISIBLE else View.GONE
        if (showLists && snap != null) {
            val y = page.gitScroll.scrollY
            fillGitLists(page.gitLists, snap)
            page.gitScroll.post { page.gitScroll.scrollTo(0, y) }
        }
        val enabled = !AppState.gitBusy
        page.gitRefresh.isEnabled = enabled
        page.gitStageAll.isEnabled = enabled
        page.gitUnstageAll.isEnabled = enabled
        page.gitPull.isEnabled = enabled
        page.gitPush.isEnabled = enabled
        page.gitFetch.isEnabled = enabled
        page.gitBranches.isEnabled = enabled
        page.gitLog.isEnabled = enabled
        page.gitInit.isEnabled = enabled
        page.gitCommit.isEnabled = enabled
        page.gitSuggest.isEnabled = enabled && snap?.isRepo == true && snap.changes.isNotEmpty()
        syncBack()
    }

    override fun onMcp() {
        if (!::binding.isInitialized) return
        binding.settingsPage.mcpStatus.text = DebugMcp.statusText()
    }

    override fun onUsage() {
        if (!::binding.isInitialized) return
        val ws = WorkspaceStore.current()
        binding.openWorkspace.text = ws.name
        binding.usageTokens.text = (ws.inputTokens + ws.outputTokens).toString()
        binding.usagePrice.text = WorkspaceStore.priceText(ws.costMicros)
        if (boardOpen) binding.boardPage.boardScope.text = ws.name
    }

    override fun onProject() {
        saveEditor(announce = false)
        editing = false
        AppState.openFile = null
        savedText = ""
        binding.filesPage.editor.setText("")
        refreshFileList()
        if (AppState.tab == AppState.Tab.GIT) refreshGit()
        if (AppState.tab == AppState.Tab.BUILD) onBuild()
        noteRepo()
    }

    private fun setupSettings() {
        binding.consolePage.openSettings.setOnClickListener { openSettings() }
        binding.settingsPage.mcpRetry.setOnClickListener {
            DebugMcp.start()
            binding.settingsPage.mcpStatus.text = DebugMcp.statusText()
        }
        binding.settingsPage.mcpStatus.text = DebugMcp.statusText()
        binding.settingsPage.saveGit.setOnClickListener { saveGitSettings() }
    }

    private fun openSettings() {
        if (boardOpen) closeBoard()
        if (workspaceOpen) closeWorkspace()
        settingsOpen = true
        binding.settingsPage.mcpStatus.text = DebugMcp.statusText()
        loadGitSettings()
        binding.settingsPage.root.visibility = View.VISIBLE
        syncBack()
    }

    private fun closeSettings() {
        if (!settingsOpen) return
        settingsOpen = false
        binding.settingsPage.root.visibility = View.GONE
        syncBack()
    }

    private fun setupUsage() {
        binding.openBoard.setOnClickListener {
            if (boardOpen) closeBoard() else openBoard()
        }
        binding.openWorkspace.setOnClickListener {
            if (workspaceOpen) closeWorkspace() else openWorkspace()
        }
        binding.workspacePage.saveWorkspace.setOnClickListener {
            saveWorkspaceName()
            onUsage()
            renderWorkspaces()
        }
        binding.workspacePage.newWorkspace.setOnClickListener { promptNewWorkspace() }
        binding.workspacePage.deleteWorkspace.setOnClickListener { confirmDeleteWorkspace() }
        binding.boardPage.addIdea.setOnClickListener { editCard(null, WorkspaceStore.Column.IDEA.id) }
        binding.boardPage.addBug.setOnClickListener { editCard(null, WorkspaceStore.Column.BUG.id) }
        binding.boardPage.addSolution.setOnClickListener { editCard(null, WorkspaceStore.Column.SOLUTION.id) }
        onUsage()
    }

    private fun openBoard() {
        if (settingsOpen) closeSettings()
        if (workspaceOpen) closeWorkspace()
        boardOpen = true
        renderBoard()
        binding.boardPage.root.visibility = View.VISIBLE
        binding.openBoard.setTextColor(getColor(R.color.accent))
        syncBack()
    }

    private fun closeBoard() {
        if (!boardOpen) return
        boardOpen = false
        binding.boardPage.root.visibility = View.GONE
        binding.openBoard.setTextColor(getColor(R.color.ink))
        syncBack()
    }

    private fun openWorkspace() {
        if (settingsOpen) closeSettings()
        if (boardOpen) closeBoard()
        workspaceOpen = true
        renderWorkspace()
        binding.workspacePage.root.visibility = View.VISIBLE
        binding.openWorkspace.setTextColor(getColor(R.color.accent))
        syncBack()
    }

    private fun closeWorkspace() {
        if (!workspaceOpen) return
        saveWorkspaceName()
        workspaceOpen = false
        binding.workspacePage.root.visibility = View.GONE
        binding.openWorkspace.setTextColor(getColor(R.color.ink))
        onUsage()
        syncBack()
    }

    private fun saveWorkspaceName() {
        if (!workspaceOpen || !::binding.isInitialized) return
        WorkspaceStore.rename(binding.workspacePage.workspaceName.text?.toString().orEmpty())
    }

    private fun renderWorkspace() {
        val ws = WorkspaceStore.current()
        binding.workspacePage.workspaceName.setText(ws.name)
        binding.workspacePage.deleteWorkspace.isEnabled = WorkspaceStore.workspaces().size > 1
        val names = WorkspaceStore.repoNames()
        binding.workspacePage.repoEmpty.visibility = if (names.isEmpty()) View.VISIBLE else View.GONE
        val repos = binding.workspacePage.repoList
        repos.removeAllViews()
        for (name in names) {
            val box = MaterialCheckBox(this).apply {
                text = name
                isChecked = name in ws.repos
                setOnCheckedChangeListener { _, checked ->
                    WorkspaceStore.setRepo(name, checked)
                }
            }
            repos.addView(box)
        }
        renderWorkspaces()
    }

    private fun renderWorkspaces() {
        val current = WorkspaceStore.current()
        val list = binding.workspacePage.workspaceList
        list.removeAllViews()
        for (ws in WorkspaceStore.workspaces()) {
            val row = layoutInflater.inflate(R.layout.row_file, list, false) as android.widget.TextView
            row.text = ws.name
            row.setTextColor(getColor(if (ws.id == current.id) R.color.accent else R.color.ink))
            row.setOnClickListener {
                if (ws.id == current.id) return@setOnClickListener
                saveWorkspaceName()
                if (WorkspaceStore.select(ws.id)) {
                    renderWorkspace()
                    onUsage()
                }
            }
            list.addView(row)
        }
    }

    private fun promptNewWorkspace() {
        saveWorkspaceName()
        val input = EditText(this).apply {
            hint = "Name"
            setSingleLine(true)
            setText("Workspace")
        }
        AlertDialog.Builder(this)
            .setTitle("New workspace")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                WorkspaceStore.create(input.text.toString())
                renderWorkspace()
                onUsage()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteWorkspace() {
        val ws = WorkspaceStore.current()
        if (WorkspaceStore.workspaces().size <= 1) return
        AlertDialog.Builder(this)
            .setTitle(ws.name)
            .setMessage("Delete this workspace and its board.")
            .setPositiveButton("Delete") { _, _ ->
                WorkspaceStore.delete(ws.id)
                renderWorkspace()
                onUsage()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderBoard() {
        val ws = WorkspaceStore.current()
        binding.boardPage.boardScope.text = ws.name
        fillColumn(binding.boardPage.ideaCards, binding.boardPage.ideaEmpty, WorkspaceStore.Column.IDEA)
        fillColumn(binding.boardPage.bugCards, binding.boardPage.bugEmpty, WorkspaceStore.Column.BUG)
        fillColumn(binding.boardPage.solutionCards, binding.boardPage.solutionEmpty, WorkspaceStore.Column.SOLUTION)
    }

    private fun fillColumn(parent: LinearLayout, empty: android.widget.TextView, column: WorkspaceStore.Column) {
        parent.removeAllViews()
        val cards = WorkspaceStore.cards(column)
        empty.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
        for (card in cards) {
            val row = RowCardBinding.inflate(layoutInflater, parent, false)
            row.cardTitle.text = card.title
            if (card.body.isBlank()) {
                row.cardBody.visibility = View.GONE
            } else {
                row.cardBody.visibility = View.VISIBLE
                row.cardBody.text = card.body
            }
            row.root.setOnClickListener { showCardMenu(card) }
            parent.addView(row.root)
        }
    }

    private fun showCardMenu(card: WorkspaceStore.Card) {
        val labels = mutableListOf("Edit")
        val actions = mutableListOf<() -> Unit>()
        actions.add { editCard(card, card.column) }
        for (column in WorkspaceStore.Column.entries) {
            if (column.id == card.column) continue
            labels.add("Move to ${column.label}")
            actions.add {
                WorkspaceStore.moveCard(card.id, column.id)
                renderBoard()
            }
        }
        labels.add("Delete")
        actions.add {
            WorkspaceStore.deleteCard(card.id)
            renderBoard()
        }
        AlertDialog.Builder(this)
            .setTitle(card.title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun editCard(existing: WorkspaceStore.Card?, column: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val title = EditText(this).apply {
            hint = "Title"
            setSingleLine(true)
            setText(existing?.title.orEmpty())
        }
        val body = EditText(this).apply {
            hint = "Note"
            minLines = 3
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(existing?.body.orEmpty())
        }
        val width = LinearLayout.LayoutParams.MATCH_PARENT
        val height = LinearLayout.LayoutParams.WRAP_CONTENT
        layout.addView(title, LinearLayout.LayoutParams(width, height))
        layout.addView(body, LinearLayout.LayoutParams(width, height))
        val dialog = AlertDialog.Builder(this)
            .setTitle(WorkspaceStore.Column.from(column).label)
            .setView(layout)
            .setPositiveButton(if (existing == null) "Add" else "Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = title.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    title.error = "Add a title"
                    return@setOnClickListener
                }
                val note = body.text?.toString()?.trim().orEmpty()
                val saved = if (existing == null) {
                    WorkspaceStore.addCard(column, text, note)
                } else {
                    WorkspaceStore.updateCard(existing.id, text, note)
                    true
                }
                if (!saved) {
                    title.error = "Board is full"
                    return@setOnClickListener
                }
                dialog.dismiss()
                renderBoard()
            }
        }
        dialog.show()
    }

    private fun noteRepo() {
        val name = runCatching { AppState.projectRoot().name }.getOrNull() ?: return
        if (!WorkspaceStore.adopt(name)) return
        onUsage()
        if (boardOpen) renderBoard()
        if (workspaceOpen) renderWorkspace()
    }

    private fun setupConsole() {
        binding.consolePage.commandRun.setOnClickListener { submitCommand() }
        binding.consolePage.commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submitCommand()
                true
            } else {
                false
            }
        }
    }

    private fun submitCommand() {
        val line = binding.consolePage.commandInput.text?.toString()?.trim().orEmpty()
        if (line.isEmpty()) return
        binding.consolePage.commandInput.setText("")
        AppState.log("$ $line")
        AppState.io.execute {
            try {
                Console.run(line)
            } catch (t: Throwable) {
                AppState.log("error: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun setupFiles() {
        binding.filesPage.filesUp.setOnClickListener { up() }
        binding.filesPage.filesOpen.setOnClickListener { pickFolder() }
        binding.filesPage.filesProjects.setOnClickListener { showProjects() }
        binding.filesPage.filesSave.setOnClickListener { saveEditor(announce = true) }
        binding.filesPage.filesClose.setOnClickListener { closeEditor(save = true) }
        binding.filesPage.fileList.setOnItemClickListener { _, _, position, _ ->
            val file = displayed.getOrNull(position) ?: return@setOnItemClickListener
            if (file.isDirectory) {
                AppState.cwd = file
                ProjectStore.remember(this, file)
                refreshFileList()
            } else {
                openEditor(file)
            }
        }
    }

    private fun setupVibe() {
        val labels = Provider.entries.map { it.label }
        val adapter = ArrayAdapter(this, R.layout.row_spinner, labels)
        adapter.setDropDownViewResource(R.layout.row_spinner)
        val spinner = binding.vibePage.provider
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!spinnerReady) return
                val next = Provider.entries[position]
                if (next == currentProvider) return
                saveProvider(currentProvider)
                currentProvider = next
                loadProvider(next)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val index = Provider.entries.indexOfFirst { it.id == store.lastProvider() }.let { if (it < 0) 0 else it }
        currentProvider = Provider.entries[index]
        spinnerReady = false
        spinner.setSelection(index)
        loadProvider(currentProvider)
        spinnerReady = true
        binding.vibePage.autoTest.isChecked = store.autoTest()
        binding.vibePage.autoTest.setOnCheckedChangeListener { _, checked -> store.setAutoTest(checked) }
        binding.vibePage.showKey.setOnCheckedChangeListener { _, checked ->
            val field = binding.vibePage.apiKey
            field.inputType = if (checked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            field.setSelection(field.text?.length ?: 0)
        }
        binding.vibePage.vibeSend.setOnClickListener { sendVibe() }
    }

    private fun setupGit() {
        val page = binding.gitPage
        page.gitRefresh.setOnClickListener {
            AppState.gitDetail = null
            refreshGit()
        }
        page.gitStageAll.setOnClickListener {
            runGit {
                AppState.gitDetail = null
                GitOps.stageAll(AppState.cwd, AppState.reposDir)
            }
        }
        page.gitUnstageAll.setOnClickListener {
            runGit {
                AppState.gitDetail = null
                GitOps.unstageAll(AppState.cwd, AppState.reposDir)
            }
        }
        page.gitPull.setOnClickListener {
            runGit {
                AppState.gitDetail = null
                GitOps.pull(AppState.cwd, AppState.reposDir)
            }
        }
        page.gitPush.setOnClickListener {
            runGit {
                AppState.gitDetail = null
                GitOps.push(AppState.cwd, AppState.reposDir)
            }
        }
        page.gitFetch.setOnClickListener {
            runGit {
                AppState.gitDetail = null
                GitOps.fetch(AppState.cwd, AppState.reposDir)
            }
        }
        page.gitBranches.setOnClickListener { showBranches() }
        page.gitLog.setOnClickListener {
            runGit {
                AppState.gitDetail = GitOps.history(AppState.cwd, AppState.reposDir)
                null
            }
        }
        page.gitInit.setOnClickListener {
            runGit {
                AppState.gitDetail = null
                val dir = runCatching { AppState.projectRoot() }.getOrElse {
                    return@runGit it.message ?: "Open a project first."
                }
                GitOps.init(dir)
            }
        }
        page.gitCommit.setOnClickListener { commitGit() }
        page.gitSuggest.setOnClickListener { suggestCommitMessage() }
        page.gitDetailClose.setOnClickListener { closeGitDetail() }
    }

    private fun setupBuild() {
        binding.buildPage.buildUrl.setText(store.buildUrl())
        binding.buildPage.buildToken.setText(store.buildToken())
        binding.buildPage.buildApk.setOnClickListener { startBuild() }
        binding.buildPage.reviseBuild.setOnClickListener { reviseBuild() }
        binding.buildPage.installApk.setOnClickListener { installBuiltApk() }
    }

    private fun saveBuildServer() {
        if (!::store.isInitialized || !::binding.isInitialized) return
        store.saveBuild(
            binding.buildPage.buildUrl.text?.toString()?.trim().orEmpty(),
            binding.buildPage.buildToken.text?.toString()?.trim().orEmpty()
        )
    }

    private fun startBuild() {
        if (AppState.buildBusy || AppState.reviseBusy) return
        saveEditor(announce = false)
        val url = binding.buildPage.buildUrl.text?.toString()?.trim().orEmpty()
        val token = binding.buildPage.buildToken.text?.toString()?.trim().orEmpty()
        saveBuildServer()
        AppState.buildBusy = true
        AppState.clearBuild()
        val appContext = applicationContext
        AppState.io.execute {
            var cloud = false
            try {
                val root = AppState.projectRoot()
                cloud = File(root, "gradlew").isFile
                DebugLog.step("build", "start path=${root.absolutePath} gradlew=$cloud")
                AppState.buildLog(RepoFiles.display(root, AppState.reposDir))
                if (cloud) {
                    if (url.isBlank() || token.isBlank()) {
                        DebugLog.step("build", "missing url or token")
                        AppState.buildLog("Paste the Cloud Run URL and build token, then press Build APK again.")
                    } else {
                        DebugLog.step("build", "mode=cloud")
                        val note: (String) -> Unit = { line ->
                            AppState.buildLog(line)
                            AppState.log(line)
                        }
                        note("Gradle project. Sending it to Cloud Run.")
                        val apk = CloudBuild.build(appContext, root, url, token, note)
                        AppState.lastApk = apk.absolutePath
                        note("")
                        note("APK")
                        note(apk.absolutePath)
                        note("Tap Install.")
                    }
                } else {
                    DebugLog.step("build", "mode=local")
                    AppState.buildLog(JsRunner.detect(root))
                    AppState.buildLog("")
                    AppState.buildLog(JsRunner.compile(root))
                    AppState.buildLog("")
                    AppState.buildLog(JsRunner.test(root))
                    AppState.buildLog("")
                    val apk = ApkPackager.packageApk(appContext, root, AppState::buildLog)
                    AppState.lastApk = apk.absolutePath
                    AppState.buildLog("")
                    AppState.buildLog("APK")
                    AppState.buildLog(apk.absolutePath)
                    AppState.buildLog("Tap Install. The new app is named Built app.")
                }
            } catch (t: Throwable) {
                DebugLog.step("build", "fail ${t.javaClass.simpleName}: ${t.message}")
                val message = "build failed: ${t.message ?: t.javaClass.simpleName}"
                AppState.buildLog(message)
                if (cloud) AppState.log(message)
            } finally {
                AppState.buildBusy = false
                UiBridge.buildUpdate()
            }
        }
    }

    private fun reviseBuild() {
        if (AppState.buildBusy || AppState.reviseBusy) return
        val log = AppState.buildText()
        if (log.isBlank()) {
            AppState.buildLog("Build first. Revise uses that log.")
            return
        }
        saveEditor(announce = false)
        saveProvider(currentProvider)
        val provider = currentProvider
        val key = store.get(provider, "key", "")
        val model = store.get(provider, "model", provider.defaultModel)
        val base = store.get(provider, "base", provider.defaultBase)
        if (key.isBlank() || model.isBlank()) {
            val message = "Add an API key on the Vibe tab, then tap Revise."
            AppState.buildLog(message)
            AppState.log(message)
            return
        }
        AppState.reviseBusy = true
        UiBridge.buildUpdate()
        val open = AppState.openFile
        val cwd = AppState.cwd
        AppState.io.execute {
            val note: (String) -> Unit = { line ->
                AppState.buildLog(line)
                AppState.log(line)
            }
            try {
                val root = AppState.projectRoot()
                DebugLog.step("revise", "start provider=${provider.id} chars=${log.length}")
                note("Revising from the build log. The API key stays on this phone.")
                val tail = log.takeLast(12_000)
                val instruction = """
                    The Gradle build failed on Cloud Run. Fix the project so it compiles. Change as little as possible. Cloud Run compiles Kotlin, Java, and Gradle, so edit those files when the log points at them.

                    Build log:
                    $tail
                """.trimIndent()
                val edit = AiClient.edit(
                    root, cwd, open, instruction, provider, key, model, base, AppState.history
                )
                note(edit.report)
                if (edit.written.isNotEmpty()) {
                    AppState.writtenPaths = edit.written.map { it.canonicalPath }
                    UiBridge.filesChanged()
                    UiBridge.vibeUpdate()
                    if (!AppState.gitBusy) {
                        AppState.gitSnapshot = runCatching {
                            GitOps.snapshot(AppState.cwd, AppState.reposDir)
                        }.getOrNull()
                        UiBridge.gitUpdate()
                    }
                }
                note("Tap Build APK to compile again.")
                DebugLog.step("revise", "done files=${edit.written.size}")
            } catch (t: Throwable) {
                DebugLog.step("revise", "fail ${t.javaClass.simpleName}: ${t.message}")
                note("revise failed: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                AppState.reviseBusy = false
                UiBridge.buildUpdate()
            }
        }
    }

    private fun installBuiltApk() {
        val path = AppState.lastApk
        val file = path?.let { File(it) }
        if (file == null || !file.isFile) {
            AppState.buildLog("APK is missing. Press Build APK again.")
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            AppState.buildLog("Allow AndVibe to install apps, then tap Install again.")
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            AppState.buildLog("install failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun setupNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (settingsOpen) closeSettings()
            if (boardOpen) closeBoard()
            if (workspaceOpen) closeWorkspace()
            val tab = when (item.itemId) {
                R.id.nav_files -> AppState.Tab.FILES
                R.id.nav_git -> AppState.Tab.GIT
                R.id.nav_vibe -> AppState.Tab.VIBE
                R.id.nav_build -> AppState.Tab.BUILD
                else -> AppState.Tab.CONSOLE
            }
            if (tab != AppState.Tab.FILES) saveEditor(announce = false)
            applyTab(tab)
            when (tab) {
                AppState.Tab.BUILD -> onBuild()
                AppState.Tab.GIT -> if (AppState.gitDetail == null) refreshGit()
                AppState.Tab.FILES -> if (!editing) refreshFileList()
                else -> Unit
            }
            true
        }
        binding.bottomNav.setOnItemReselectedListener {
            if (settingsOpen) closeSettings()
            if (boardOpen) closeBoard()
            if (workspaceOpen) closeWorkspace()
        }
        applyTab(AppState.tab)
        val navId = when (AppState.tab) {
            AppState.Tab.FILES -> R.id.nav_files
            AppState.Tab.GIT -> R.id.nav_git
            AppState.Tab.VIBE -> R.id.nav_vibe
            AppState.Tab.BUILD -> R.id.nav_build
            AppState.Tab.CONSOLE -> R.id.nav_console
        }
        if (binding.bottomNav.selectedItemId != navId) binding.bottomNav.selectedItemId = navId
    }

    private fun applyTab(tab: AppState.Tab) {
        AppState.tab = tab
        binding.consolePage.root.visibility = if (tab == AppState.Tab.CONSOLE) View.VISIBLE else View.GONE
        binding.filesPage.root.visibility = if (tab == AppState.Tab.FILES) View.VISIBLE else View.GONE
        binding.gitPage.root.visibility = if (tab == AppState.Tab.GIT) View.VISIBLE else View.GONE
        binding.vibePage.root.visibility = if (tab == AppState.Tab.VIBE) View.VISIBLE else View.GONE
        binding.buildPage.root.visibility = if (tab == AppState.Tab.BUILD) View.VISIBLE else View.GONE
    }

    private fun refreshFileList() {
        val cwd = AppState.cwd
        val repos = AppState.reposDir
        binding.filesPage.filesPath.text = RepoFiles.display(cwd, repos)
        binding.filesPage.filesUp.isEnabled = cwd.canonicalPath != repos.canonicalPath
        val files = cwd.listFiles()
            ?.filter { it.name != ".git" }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .orEmpty()
        displayed = files
        binding.filesPage.fileList.adapter = ArrayAdapter(
            this,
            R.layout.row_file,
            files.map { if (it.isDirectory) it.name + "/" else it.name }
        )
        binding.filesPage.filesEmpty.text = if (cwd.canonicalPath == repos.canonicalPath) {
            "Clone a repo, or tap Open for a folder already on this phone."
        } else {
            "Empty folder"
        }
        binding.filesPage.fileList.emptyView = binding.filesPage.filesEmpty
        if (!editing) {
            binding.filesPage.editor.visibility = View.GONE
            binding.filesPage.fileList.visibility = View.VISIBLE
            binding.filesPage.filesUp.visibility = View.VISIBLE
            binding.filesPage.filesOpen.visibility = View.VISIBLE
            binding.filesPage.filesProjects.visibility = View.VISIBLE
            binding.filesPage.filesSave.visibility = View.GONE
            binding.filesPage.filesClose.visibility = View.GONE
            syncBack()
        }
    }

    private fun up() {
        if (editing) return
        val parent = AppState.cwd.parentFile?.canonicalFile ?: return
        val repos = AppState.reposDir.canonicalFile
        if (parent != repos && !parent.path.startsWith(repos.path + File.separator)) return
        AppState.cwd = parent
        refreshFileList()
    }

    private fun openEditor(file: File) {
        if (!file.isFile) {
            AppState.log("not a file: ${file.name}")
            return
        }
        if (file.length() > 256_000) {
            AppState.log("file too large to edit: ${file.name}")
            return
        }
        if (RepoFiles.looksBinary(file)) {
            AppState.log("binary file: ${file.name}")
            return
        }
        saveEditor(announce = false)
        val text = file.readText()
        AppState.openFile = file
        savedText = text
        editing = true
        binding.filesPage.editor.setText(text)
        val rel = RepoFiles.rel(file, AppState.reposDir)
        binding.filesPage.filesPath.text = if (rel.isEmpty()) file.name else "~/$rel"
        binding.filesPage.fileList.emptyView = null
        binding.filesPage.filesEmpty.visibility = View.GONE
        binding.filesPage.fileList.visibility = View.GONE
        binding.filesPage.editor.visibility = View.VISIBLE
        binding.filesPage.filesUp.visibility = View.GONE
        binding.filesPage.filesOpen.visibility = View.GONE
        binding.filesPage.filesProjects.visibility = View.GONE
        binding.filesPage.filesSave.visibility = View.VISIBLE
        binding.filesPage.filesClose.visibility = View.VISIBLE
        syncBack()
    }

    private fun closeEditor(save: Boolean) {
        if (save) saveEditor(announce = true)
        editing = false
        AppState.openFile = null
        savedText = ""
        binding.filesPage.editor.setText("")
        refreshFileList()
    }

    private fun saveEditor(announce: Boolean) {
        if (!editing) return
        val file = AppState.openFile ?: return
        val text = binding.filesPage.editor.text?.toString() ?: return
        if (text == savedText) return
        try {
            file.parentFile?.mkdirs()
            file.writeText(text)
            savedText = text
            if (announce) AppState.log("saved ~/${RepoFiles.rel(file, AppState.reposDir)}")
        } catch (e: Exception) {
            AppState.log("save failed: ${e.message}")
        }
    }

    private fun reloadOpen(paths: List<String>) {
        val file = AppState.openFile ?: return
        if (!editing) return
        if (paths.none { it == file.canonicalPath }) return
        val text = file.readText()
        savedText = text
        binding.filesPage.editor.setText(text)
    }

    private fun loadProvider(provider: Provider) {
        binding.vibePage.apiKey.setText(store.get(provider, "key", ""))
        binding.vibePage.model.setText(store.get(provider, "model", provider.defaultModel))
        binding.vibePage.baseUrl.setText(store.get(provider, "base", provider.defaultBase))
    }

    private fun saveProvider(provider: Provider) {
        store.save(
            provider,
            binding.vibePage.apiKey.text?.toString()?.trim().orEmpty(),
            binding.vibePage.model.text?.toString()?.trim().orEmpty(),
            binding.vibePage.baseUrl.text?.toString()?.trim().orEmpty()
        )
    }

    private fun sendVibe() {
        if (AppState.vibeBusy) return
        val instruction = binding.vibePage.vibePrompt.text?.toString()?.trim().orEmpty()
        if (instruction.isEmpty()) {
            binding.vibePage.vibeResult.text = "Write what you want changed."
            return
        }
        saveEditor(announce = false)
        saveProvider(currentProvider)
        val provider = currentProvider
        val key = binding.vibePage.apiKey.text?.toString()?.trim().orEmpty()
        val model = binding.vibePage.model.text?.toString()?.trim().orEmpty()
        val base = binding.vibePage.baseUrl.text?.toString()?.trim().orEmpty()
        val auto = binding.vibePage.autoTest.isChecked
        val open = AppState.openFile
        val cwd = AppState.cwd
        AppState.vibeBusy = true
        AppState.vibeResult = ""
        AppState.writtenPaths = emptyList()
        UiBridge.vibeUpdate()
        AppState.io.execute {
            DebugLog.step("vibe", "start provider=${provider.id} chars=${instruction.length}")
            try {
                val root = AppState.projectRoot()
                val edit = AiClient.edit(
                    root, cwd, open, instruction, provider, key, model, base, AppState.history
                )
                val report = buildString {
                    append(edit.report)
                    if (edit.written.isNotEmpty()) {
                        if (!AppState.gitBusy) {
                            AppState.gitSnapshot = runCatching {
                                GitOps.snapshot(AppState.cwd, AppState.reposDir)
                            }.getOrNull()
                            UiBridge.gitUpdate()
                        }
                    }
                    if (auto && edit.written.isNotEmpty()) {
                        append("\n\n")
                        append(JsRunner.compile(root))
                        append('\n')
                        append(JsRunner.test(root))
                    }
                }
                AppState.vibeResult = report
                AppState.writtenPaths = edit.written.map { it.canonicalPath }
                DebugLog.step("vibe", "done files=${edit.written.size}")
                AppState.log(report)
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                AppState.vibeResult = msg
                AppState.writtenPaths = emptyList()
                DebugLog.step("vibe", "fail ${t.javaClass.simpleName}: $msg")
                AppState.log("vibe error: $msg")
            }             finally {
                AppState.vibeBusy = false
                UiBridge.vibeUpdate()
            }
        }
    }

    private fun syncBack() {
        backCallback.isEnabled = settingsOpen || boardOpen || workspaceOpen || editing || AppState.gitDetail != null
    }

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        openFolder.launch(intent)
    }

    private fun startImport(uri: Uri) {
        saveEditor(announce = false)
        val app = applicationContext
        AppState.log("importing folder…")
        AppState.io.execute {
            try {
                val dest = FolderImport.importTree(app, uri, AppState.reposDir, AppState::log)
                AppState.cwd = dest.canonicalFile
                ProjectStore.remember(app, AppState.cwd)
                AppState.gitDetail = null
                AppState.gitSnapshot = null
                UiBridge.projectChanged()
            } catch (t: Throwable) {
                AppState.log("open failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun showProjects() {
        val dirs = AppState.reposDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        if (dirs.isEmpty()) {
            AppState.log("No saved projects. Clone one, or tap Open.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Projects")
            .setItems(dirs.map { it.name }.toTypedArray()) { _, which -> openProject(dirs[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openProject(dir: File) {
        if (editing) closeEditor(save = true)
        AppState.cwd = dir.canonicalFile
        ProjectStore.remember(this, AppState.cwd)
        AppState.gitDetail = null
        AppState.gitSnapshot = null
        refreshFileList()
        if (AppState.tab == AppState.Tab.GIT) refreshGit()
        AppState.log("opened ${dir.name}")
        noteRepo()
    }

    private fun refreshGit() {
        if (AppState.gitBusy) return
        AppState.gitBusy = true
        onGit()
        AppState.io.execute {
            AppState.gitSnapshot = runCatching {
                GitOps.snapshot(AppState.cwd, AppState.reposDir)
            }.getOrElse {
                GitOps.Snapshot("", it.message ?: "git failed", emptyList(), false)
            }
            AppState.gitBusy = false
            UiBridge.gitUpdate()
        }
    }

    private fun closeGitDetail() {
        AppState.gitDetail = null
        onGit()
    }

    private fun runGit(block: () -> String?) {
        if (AppState.gitBusy) return
        saveEditor(announce = false)
        AppState.gitBusy = true
        onGit()
        AppState.io.execute {
            DebugLog.step("git", "start")
            val msg = try {
                block()
            } catch (t: Throwable) {
                DebugLog.step("git", "fail ${t.javaClass.simpleName}: ${t.message}")
                t.message ?: t.javaClass.simpleName
            }
            if (!msg.isNullOrBlank()) {
                DebugLog.step("git", "result ${msg.lineSequence().firstOrNull().orEmpty()}")
                AppState.log(msg)
            }
            AppState.gitSnapshot = runCatching {
                GitOps.snapshot(AppState.cwd, AppState.reposDir)
            }.getOrElse {
                GitOps.Snapshot("", it.message ?: "git failed", emptyList(), false)
            }
            AppState.gitBusy = false
            UiBridge.gitUpdate()
            UiBridge.filesChanged()
        }
    }

    private fun commitGit() {
        val message = binding.gitPage.gitMessage.text?.toString()?.trim().orEmpty()
        if (message.isEmpty()) {
            binding.gitPage.gitSummary.text = "Write a commit message."
            return
        }
        runGit {
            AppState.gitDetail = null
            val result = GitOps.commit(AppState.cwd, AppState.reposDir, message)
            if (result.startsWith("committed")) AppState.gitMessageClear = true
            result
        }
    }

    private fun loadGitSettings() {
        val page = binding.settingsPage
        page.gitName.setText(GitOps.authorName)
        page.gitEmail.setText(GitOps.authorEmail)
        page.gitHttpsUser.setText(GitOps.remoteUser)
        page.gitHttpsToken.setText(GitOps.remoteToken)
        page.gitSsh.setText(store.gitSsh())
        page.gitOrigin.setText(
            runCatching { GitOps.originUrl(AppState.cwd, AppState.reposDir) }.getOrDefault("")
        )
        page.gitSettingsNote.text = ""
    }

    private fun saveGitSettings() {
        val page = binding.settingsPage
        GitOps.authorName = page.gitName.text?.toString()?.trim().orEmpty().ifBlank { "AndVibe" }
        GitOps.authorEmail = page.gitEmail.text?.toString()?.trim().orEmpty().ifBlank { "andvibe@local" }
        GitOps.remoteUser = page.gitHttpsUser.text?.toString()?.trim().orEmpty()
        GitOps.remoteToken = page.gitHttpsToken.text?.toString().orEmpty()
        val ssh = page.gitSsh.text?.toString().orEmpty()
        store.saveGit(GitOps.authorName, GitOps.authorEmail, GitOps.remoteUser, GitOps.remoteToken, ssh)
        val origin = page.gitOrigin.text?.toString()?.trim().orEmpty()
        val note = if (origin.isEmpty()) {
            "Saved the account."
        } else {
            runCatching { GitOps.setOrigin(AppState.cwd, AppState.reposDir, origin) }
                .getOrElse { it.message ?: "could not set origin" }
        }
        page.gitSettingsNote.text = note
        AppState.log(note)
        if (AppState.tab == AppState.Tab.GIT) refreshGit()
    }

    private fun fillGitLists(parent: LinearLayout, snap: GitOps.Snapshot) {
        parent.removeAllViews()
        gitHeading(parent, "Changes")
        val unstaged = snap.changes.filter { it.unstaged }
        if (unstaged.isEmpty()) gitNote(parent, "No unstaged changes")
        else unstaged.forEach { gitFileRow(parent, it, stage = true) }
        gitHeading(parent, "Staged")
        val staged = snap.changes.filter { it.staged }
        if (staged.isEmpty()) gitNote(parent, "Nothing staged")
        else staged.forEach { gitFileRow(parent, it, stage = false) }
        gitHeading(parent, "Commits")
        if (snap.commits.isEmpty()) gitNote(parent, "No commits yet")
        else snap.commits.forEach { gitCommitRow(parent, it) }
    }

    private fun gitHeading(parent: LinearLayout, title: String) {
        val view = TextView(this)
        view.text = title
        view.setTextColor(ContextCompat.getColor(this, R.color.ink))
        view.textSize = 14f
        val top = if (parent.childCount == 0) 0 else (14 * resources.displayMetrics.density).toInt()
        view.setPadding(0, top, 0, (4 * resources.displayMetrics.density).toInt())
        parent.addView(view)
    }

    private fun gitNote(parent: LinearLayout, text: String) {
        val view = TextView(this)
        view.text = text
        view.setTextColor(ContextCompat.getColor(this, R.color.muted))
        view.textSize = 13f
        view.setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
        parent.addView(view)
    }

    private fun gitFileRow(parent: LinearLayout, change: GitOps.Change, stage: Boolean) {
        val row = RowGitFileBinding.inflate(layoutInflater, parent, false)
        row.gitPath.text = change.label
        row.gitMark.text = if (stage) "+" else "−"
        row.gitPath.setOnClickListener { showChangeMenu(change) }
        row.gitMark.setOnClickListener {
            runGit {
                AppState.gitDetail = null
                if (stage) GitOps.stage(AppState.cwd, AppState.reposDir, change.path)
                else GitOps.unstage(AppState.cwd, AppState.reposDir, change.path)
            }
        }
        parent.addView(row.root)
    }

    private fun gitCommitRow(parent: LinearLayout, commit: GitOps.CommitLine) {
        val view = TextView(this)
        view.text = "${commit.id}  ${commit.whenText}  ${commit.subject}"
        view.setTextColor(ContextCompat.getColor(this, R.color.ink))
        view.textSize = 13f
        view.typeface = android.graphics.Typeface.MONOSPACE
        val pad = (6 * resources.displayMetrics.density).toInt()
        view.setPadding(0, pad, 0, pad)
        view.setOnClickListener {
            AppState.gitDetail = "${commit.id} ${commit.whenText}\n${commit.subject}"
            onGit()
        }
        parent.addView(view)
    }

    private fun suggestCommitMessage() {
        if (AppState.gitBusy) return
        val snap = AppState.gitSnapshot
        if (snap == null || !snap.isRepo || snap.changes.isEmpty()) {
            binding.gitPage.gitSummary.text = "Nothing to describe."
            return
        }
        saveProvider(currentProvider)
        val provider = currentProvider
        val key = store.get(provider, "key", "")
        val model = store.get(provider, "model", provider.defaultModel)
        val base = store.get(provider, "base", provider.defaultBase)
        if (key.isBlank() || model.isBlank()) {
            AppState.log("Add an API key on the Vibe tab, then tap Message.")
            return
        }
        saveEditor(announce = false)
        AppState.gitBusy = true
        onGit()
        val cwd = AppState.cwd
        val repos = AppState.reposDir
        AppState.io.execute {
            val text = try {
                val files = snap.changes.joinToString("\n") { it.label }
                val diff = GitOps.diff(cwd, repos, null, false).take(4000)
                val staged = GitOps.diff(cwd, repos, null, true).take(2000)
                val raw = AiClient.complete(
                    "Reply with one git commit subject and nothing else. No quotes.",
                    "Changes:\n$files\n\nUnstaged diff:\n$diff\n\nStaged diff:\n$staged",
                    provider,
                    key,
                    model,
                    base
                )
                raw.lineSequence().map { it.trim().trim('"') }.firstOrNull { it.isNotEmpty() }.orEmpty()
                    .let { if (it.length <= 72) it else it.take(69).trimEnd() + "..." }
            } catch (t: Throwable) {
                AppState.log(t.message ?: "could not write a message")
                ""
            }
            AppState.gitBusy = false
            runOnUiThread {
                if (!isFinishing && text.isNotEmpty()) binding.gitPage.gitMessage.setText(text)
                onGit()
            }
        }
    }

    private fun showBranches() {
        if (AppState.gitBusy) return
        AppState.io.execute {
            val names = try {
                GitOps.branches(AppState.cwd, AppState.reposDir)
            } catch (t: Throwable) {
                AppState.log(t.message ?: "git failed")
                null
            } ?: return@execute
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val items = (names + "New branch").toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Branch")
                    .setItems(items) { _, which ->
                        if (which == names.size) promptNewBranch()
                        else runGit {
                            AppState.gitDetail = null
                            GitOps.checkout(AppState.cwd, AppState.reposDir, names[which])
                        }
                    }
                    .show()
            }
        }
    }

    private fun promptNewBranch() {
        val input = EditText(this).apply {
            hint = "branch name"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("New branch")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString()
                runGit {
                    AppState.gitDetail = null
                    GitOps.createBranch(AppState.cwd, AppState.reposDir, name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showChangeMenu(change: GitOps.Change) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun add(label: String, action: () -> Unit) {
            labels.add(label)
            actions.add(action)
        }
        if (change.unstaged) add("Diff") { showGitDiff(change, staged = false) }
        if (change.staged) add("Diff staged") { showGitDiff(change, staged = true) }
        if (change.unstaged) add("Stage") {
            runGit {
                AppState.gitDetail = null
                GitOps.stage(AppState.cwd, AppState.reposDir, change.path)
            }
        }
        if (change.staged) add("Unstage") {
            runGit {
                AppState.gitDetail = null
                GitOps.unstage(AppState.cwd, AppState.reposDir, change.path)
            }
        }
        add("Discard") { confirmDiscard(change) }
        add("Open") { openGitPath(change) }
        AlertDialog.Builder(this)
            .setTitle(change.path)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun showGitDiff(change: GitOps.Change, staged: Boolean) {
        runGit {
            AppState.gitDetail = GitOps.diff(AppState.cwd, AppState.reposDir, change.path, staged)
            null
        }
    }

    private fun confirmDiscard(change: GitOps.Change) {
        AlertDialog.Builder(this)
            .setTitle("Discard changes")
            .setMessage(change.path)
            .setPositiveButton("Discard") { _, _ ->
                runGit {
                    AppState.gitDetail = null
                    GitOps.discard(AppState.cwd, AppState.reposDir, change.path)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openGitPath(change: GitOps.Change) {
        val root = RepoFiles.gitRoot(AppState.cwd, AppState.reposDir) ?: return
        val file = File(root, change.path)
        if (file.isDirectory) {
            AppState.cwd = file
            binding.bottomNav.selectedItemId = R.id.nav_files
            refreshFileList()
            return
        }
        if (!file.isFile) {
            AppState.log("missing ${change.path}")
            return
        }
        binding.bottomNav.selectedItemId = R.id.nav_files
        openEditor(file)
    }
}
