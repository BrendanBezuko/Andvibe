package com.example.andvibe

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
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
import com.example.andvibe.databinding.RowFossBinding
import com.example.andvibe.databinding.RowGitFileBinding
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), UiBridge.Listener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SecretStore
    private var currentProvider = Provider.OPENAI
    private var spinnerReady = false
    private var savedText = ""
    private var editing = false
    private var displayed = emptyList<File>()
    private val vault = mutableMapOf<String, EditText>()
    private val apiKeys = linkedMapOf<Provider, EditText>()
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
            if (settingsOpen) saveApiKeys(announce = false)
            saveProvider(currentProvider)
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
        binding.consolePage.logView.text = paintLog(AppState.text())
        paintTape()
        if (nearBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun paintTape() {
        val project = runCatching { AppState.projectRoot().name }.getOrDefault("—")
        binding.consolePage.consoleProject.text = project
        binding.consolePage.consoleClock.text = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    }

    private fun paintLog(raw: String): CharSequence {
        if (raw.isEmpty()) return raw
        val span = SpannableString(raw)
        val normal = getColor(R.color.tape_ink)
        val bid = getColor(R.color.bid)
        val ask = getColor(R.color.ask)
        val quote = getColor(R.color.quote)
        var start = 0
        while (start <= raw.length) {
            val newline = raw.indexOf('\n', start)
            val end = if (newline < 0) raw.length else newline
            if (end > start) {
                val line = raw.substring(start, end)
                val color = when {
                    line.startsWith("$ ") -> quote
                    line.startsWith("error", ignoreCase = true) ||
                        line.contains("failed", ignoreCase = true) -> ask
                    line.startsWith("saved") || line.startsWith("staged") ||
                        line.startsWith("unstaged") || line.startsWith("tagged") ||
                        line.startsWith("pushed") || line.startsWith("pulled") -> bid
                    else -> normal
                }
                span.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (newline < 0) break
            start = newline + 1
        }
        return span
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
        if (!AppState.vibeBusy && AppState.vibeResult.isNotEmpty()) {
            AppState.chat.add("assistant" to AppState.vibeResult)
            AppState.vibeResult = ""
        }
        renderChat()
        binding.vibePage.vibeSend.isEnabled = !AppState.vibeBusy
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
        binding.buildPage.buildLog.text = text
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
        binding.settingsPage.saveApiKeys.setOnClickListener { saveApiKeys(announce = true) }
        binding.settingsPage.showApiKeys.setOnCheckedChangeListener { _, checked ->
            val type = if (checked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            for (field in apiKeys.values) {
                field.inputType = type
                field.setSelection(field.text?.length ?: 0)
            }
        }
        buildApiKeyFields()
    }

    private fun openSettings() {
        if (boardOpen) closeBoard()
        if (workspaceOpen) closeWorkspace()
        settingsOpen = true
        binding.settingsPage.mcpStatus.text = DebugMcp.statusText()
        loadGitSettings()
        loadApiKeys()
        binding.settingsPage.root.visibility = View.VISIBLE
        syncBack()
    }

    private fun closeSettings() {
        if (!settingsOpen) return
        saveApiKeys(announce = false)
        settingsOpen = false
        binding.settingsPage.root.visibility = View.GONE
        syncBack()
    }

    private fun buildApiKeyFields() {
        val parent = binding.settingsPage.apiKeyList
        if (parent.childCount > 0) return
        for (provider in Provider.entries) {
            val field = EditText(this).apply {
                hint = "${provider.label} API key"
                setHintTextColor(getColor(R.color.muted))
                setTextColor(getColor(R.color.ink))
                setBackgroundResource(R.drawable.bg_field)
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                maxLines = 1
                setPadding(dp(10), dp(10), dp(10), dp(10))
                textSize = 14f
                setText(store.get(provider, "key", ""))
            }
            apiKeys[provider] = field
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = dp(8)
            parent.addView(field, params)
        }
    }

    private fun loadApiKeys() {
        for ((provider, field) in apiKeys) {
            field.setText(store.get(provider, "key", ""))
        }
        binding.settingsPage.keyNote.text = ""
    }

    private fun saveApiKeys(announce: Boolean) {
        if (apiKeys.isEmpty()) return
        for ((provider, field) in apiKeys) {
            val key = field.text?.toString()?.trim().orEmpty()
            store.saveKey(provider, key)
            vault["${provider.id}_key"]?.setText(key)
        }
        if (announce) binding.settingsPage.keyNote.text = "Saved."
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
        val page = binding.consolePage
        page.commandRun.setOnClickListener { submitCommand() }
        page.commandInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submitCommand()
                true
            } else {
                false
            }
        }
        page.consoleTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showConsoleTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        page.saveSecrets.setOnClickListener { saveSecrets() }
        page.saveVariables.setOnClickListener { saveVariables() }
        page.findGo.setOnClickListener { findRepos() }
        page.findQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                findRepos()
                true
            } else {
                false
            }
        }
        buildVault()
        renderFind()
    }

    private fun showConsoleTab(index: Int) {
        val page = binding.consolePage
        page.terminalPane.visibility = if (index == 0) View.VISIBLE else View.GONE
        page.secretsPane.visibility = if (index == 1) View.VISIBLE else View.GONE
        page.variablesPane.visibility = if (index == 2) View.VISIBLE else View.GONE
        if (index != 0) {
            saveProvider(currentProvider)
            loadVault()
        }
    }

    private fun buildVault() {
        val secrets = binding.consolePage.secretsList
        val variables = binding.consolePage.variablesList
        vaultNote(secrets, "API keys, the build token, and git credentials. They stay encrypted on this device.")
        for (provider in Provider.entries) {
            vaultField(secrets, "${provider.id}_key", "${provider.label} API key", secret = true)
        }
        vaultField(secrets, "build_token", "Build token", secret = true)
        vaultField(secrets, "git_token", "Git HTTPS token", secret = true)
        vaultField(secrets, "git_ssh", "SSH private key", secret = true, lines = 4)

        vaultNote(variables, "Models, base URLs, the Cloud Run address, and git identity for the open project.")
        for (provider in Provider.entries) {
            vaultField(variables, "${provider.id}_model", "${provider.label} model", secret = false)
            vaultField(variables, "${provider.id}_base", "${provider.label} base URL", secret = false)
        }
        vaultField(variables, "build_url", "Build service URL", secret = false)
        vaultField(variables, "git_name", "Git name", secret = false)
        vaultField(variables, "git_email", "Git email", secret = false)
        vaultField(variables, "git_user", "Git HTTPS user", secret = false)
        vaultField(variables, "git_origin", "Remote URL", secret = false)
        loadVault()
    }

    private fun vaultNote(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.muted))
            textSize = 13f
        })
    }

    private fun vaultField(parent: LinearLayout, key: String, hint: String, secret: Boolean, lines: Int = 1) {
        val field = EditText(this).apply {
            this.hint = hint
            setHintTextColor(getColor(R.color.muted))
            setTextColor(getColor(R.color.ink))
            setBackgroundResource(R.drawable.bg_field)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setPadding(dp(10), dp(10), dp(10), dp(10))
            textSize = 14f
            if (lines > 1) {
                minLines = lines
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else if (secret) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                maxLines = 1
            } else {
                inputType = InputType.TYPE_CLASS_TEXT
                maxLines = 1
            }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(8)
        parent.addView(field, params)
        vault[key] = field
    }

    private fun loadVault() {
        for (provider in Provider.entries) {
            vault["${provider.id}_key"]?.setText(store.get(provider, "key", ""))
            vault["${provider.id}_model"]?.setText(store.get(provider, "model", provider.defaultModel))
            vault["${provider.id}_base"]?.setText(store.get(provider, "base", provider.defaultBase))
        }
        vault["build_token"]?.setText(store.buildToken())
        vault["build_url"]?.setText(store.buildUrl())
        vault["git_token"]?.setText(store.gitToken())
        vault["git_ssh"]?.setText(store.gitSsh())
        vault["git_name"]?.setText(store.gitName())
        vault["git_email"]?.setText(store.gitEmail())
        vault["git_user"]?.setText(store.gitUser())
        vault["git_origin"]?.setText(
            runCatching { GitOps.originUrl(AppState.cwd, AppState.reposDir) }.getOrDefault("")
        )
    }

    private fun saveSecrets() {
        val selected = currentProvider
        for (provider in Provider.entries) {
            val key = vaultText("${provider.id}_key")
            store.saveProvider(
                provider,
                key,
                store.get(provider, "model", provider.defaultModel),
                store.get(provider, "base", provider.defaultBase),
                select = provider == selected
            )
            apiKeys[provider]?.setText(key)
        }
        val token = vaultText("build_token")
        val gitToken = vaultText("git_token")
        val ssh = vaultText("git_ssh")
        store.saveBuild(store.buildUrl(), token)
        store.saveGit(store.gitName(), store.gitEmail(), store.gitUser(), gitToken, ssh)
        GitOps.remoteToken = gitToken
        binding.settingsPage.gitHttpsToken.setText(gitToken)
        binding.settingsPage.gitSsh.setText(ssh)
        AppState.log("saved secrets")
    }

    private fun saveVariables() {
        val selected = currentProvider
        for (provider in Provider.entries) {
            store.saveProvider(
                provider,
                store.get(provider, "key", ""),
                vaultText("${provider.id}_model"),
                vaultText("${provider.id}_base"),
                select = provider == selected
            )
        }
        val url = vaultText("build_url")
        val name = vaultText("git_name")
        val email = vaultText("git_email")
        val user = vaultText("git_user")
        val origin = vaultText("git_origin")
        store.saveBuild(url, store.buildToken())
        store.saveGit(name, email, user, store.gitToken(), store.gitSsh())
        GitOps.authorName = name.ifBlank { "AndVibe" }
        GitOps.authorEmail = email.ifBlank { "andvibe@local" }
        GitOps.remoteUser = user
        binding.vibePage.model.setText(vaultText("${selected.id}_model"))
        binding.vibePage.baseUrl.setText(vaultText("${selected.id}_base"))
        binding.settingsPage.gitName.setText(name)
        binding.settingsPage.gitEmail.setText(email)
        binding.settingsPage.gitHttpsUser.setText(user)
        binding.settingsPage.gitOrigin.setText(origin)
        AppState.io.execute {
            val current = runCatching { GitOps.originUrl(AppState.cwd, AppState.reposDir) }.getOrDefault("")
            val message = if (origin == current) {
                "saved variables"
            } else {
                GitOps.setOrigin(AppState.cwd, AppState.reposDir, origin)
            }
            runOnUiThread { AppState.log(message) }
        }
    }

    private fun vaultText(key: String): String = vault[key]?.text?.toString()?.trim().orEmpty()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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

    private fun findRepos() {
        if (AppState.findBusy) return
        val query = binding.consolePage.findQuery.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            AppState.findNote = "Say what you want to find."
            renderFind()
            return
        }
        saveProvider(currentProvider)
        val provider = currentProvider
        val key = store.get(provider, "key", "")
        val model = store.get(provider, "model", provider.defaultModel)
        val base = store.get(provider, "base", provider.defaultBase)
        AppState.findBusy = true
        AppState.findHits = emptyList()
        AppState.findNote = "Searching GitHub, GitLab, and Codeberg…"
        renderFind()
        DebugLog.step("find", "start chars=${query.length} provider=${provider.id}")
        AppState.io.execute {
            val result = try {
                FossSearch.search(query, provider, key, model, base)
            } catch (t: Throwable) {
                DebugLog.step("find", "fail ${t.javaClass.simpleName}: ${t.message}")
                FossSearch.SearchResult(emptyList(), t.message ?: "search failed")
            }
            AppState.findHits = result.hits
            AppState.findNote = result.note
            AppState.findBusy = false
            DebugLog.step("find", "done hits=${result.hits.size}")
            runOnUiThread {
                if (!isFinishing) renderFind()
            }
        }
    }

    private fun renderFind() {
        val page = binding.consolePage
        page.findGo.isEnabled = !AppState.findBusy
        page.findGo.text = if (AppState.findBusy) "Finding…" else "Find"
        val note = AppState.findNote
        page.findStatus.text = note
        page.findStatus.visibility = if (note.isBlank()) View.GONE else View.VISIBLE
        val hits = AppState.findHits
        page.findScroll.visibility = if (hits.isEmpty()) View.GONE else View.VISIBLE
        page.findResults.removeAllViews()
        for (hit in hits) {
            val row = RowFossBinding.inflate(layoutInflater, page.findResults, false)
            row.fossName.text = hit.name
            row.fossMeta.text = "${hostOf(hit.page)} · ${hit.stars} stars"
            row.fossWhy.text = hit.why.ifBlank { hit.blurb }
            row.root.setOnClickListener { downloadHit(hit) }
            page.findResults.addView(row.root)
        }
    }

    private fun hostOf(url: String): String {
        val host = try {
            java.net.URI(url).host?.lowercase()?.removePrefix("www.")
        } catch (_: Exception) {
            null
        }
        return when (host) {
            "github.com" -> "GitHub"
            "gitlab.com" -> "GitLab"
            "codeberg.org" -> "Codeberg"
            else -> host ?: "repo"
        }
    }

    private fun downloadHit(hit: FossSearch.RepoHit) {
        if (AppState.downloadBusy) return
        if (editing) closeEditor(save = true)
        AppState.downloadBusy = true
        AppState.downloadNote = "Downloading ${hit.name}…"
        saveEditor(announce = false)
        binding.bottomNav.selectedItemId = R.id.nav_files
        refreshFileList()
        AppState.log("clone ${hit.cloneUrl}")
        val app = applicationContext
        AppState.io.execute {
            var failed: String? = null
            try {
                val url = GitClient.normalizeGitUrl(hit.cloneUrl)
                val name = GitClient.repoNameFromUrl(url)
                val dest = File(AppState.reposDir, name)
                if (dest.isDirectory && !dest.list().isNullOrEmpty()) {
                    AppState.cwd = dest.canonicalFile
                    ProjectStore.remember(app, AppState.cwd)
                    AppState.log("already in ~/$name")
                } else {
                    GitClient.clone(url, dest, AppState::log)
                    AppState.cwd = dest.canonicalFile
                    ProjectStore.remember(app, AppState.cwd)
                }
                AppState.gitDetail = null
                AppState.gitSnapshot = null
                AppState.openFile = null
                UiBridge.projectChanged()
            } catch (t: Throwable) {
                failed = "clone failed: ${t.message ?: t.javaClass.simpleName}"
                AppState.log(failed)
            } finally {
                AppState.downloadBusy = false
                AppState.downloadNote = null
                UiBridge.filesChanged()
            }
            val message = failed ?: return@execute
            runOnUiThread {
                if (!isFinishing && !editing) binding.filesPage.filesPath.text = message
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
        binding.vibePage.vibeSend.setOnClickListener { sendVibe() }
        binding.vibePage.vibePrompt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendVibe()
                true
            } else {
                false
            }
        }
        binding.vibePage.vibeTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showVibeTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun showVibeTab(index: Int) {
        val model = index == 1
        if (!model) saveProvider(currentProvider)
        binding.vibePage.chatPane.visibility = if (model) View.GONE else View.VISIBLE
        binding.vibePage.modelPane.visibility = if (model) View.VISIBLE else View.GONE
    }

    private fun renderChat() {
        val list = binding.vibePage.chatList
        list.removeAllViews()
        val turns = AppState.chat.toList()
        val working = AppState.vibeBusy
        binding.vibePage.chatEmpty.visibility = if (turns.isEmpty() && !working) View.VISIBLE else View.GONE
        for ((role, text) in turns) list.addView(chatBubble(role, text))
        if (working) list.addView(chatBubble("assistant", "Working…"))
        binding.vibePage.chatScroll.post { binding.vibePage.chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun chatBubble(role: String, text: String): View {
        val user = role == "user"
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.ink))
            textSize = 15f
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(8)
        params.gravity = if (user) Gravity.END else Gravity.START
        val max = (resources.displayMetrics.widthPixels * 0.82f).toInt()
        bubble.maxWidth = max
        bubble.layoutParams = params
        return bubble
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
        page.gitTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showGitTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (tab.position == 1) loadReleases()
            }
        })
        page.createRelease.setOnClickListener { createRelease() }
    }

    private fun showGitTab(index: Int) {
        val releases = index == 1
        binding.gitPage.gitStatus.visibility = if (releases) View.GONE else View.VISIBLE
        binding.gitPage.gitReleases.visibility = if (releases) View.VISIBLE else View.GONE
        if (releases) loadReleases()
    }

    private fun loadReleases() {
        AppState.io.execute {
            val tags = runCatching { GitOps.tags(AppState.cwd, AppState.reposDir) }.getOrDefault(emptyList())
            runOnUiThread { renderReleases(tags) }
        }
    }

    private fun renderReleases(tags: List<GitOps.TagLine>) {
        val list = binding.gitPage.releaseList
        list.removeAllViews()
        binding.gitPage.releaseEmpty.visibility = if (tags.isEmpty()) View.VISIBLE else View.GONE
        for (tag in tags) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            card.addView(TextView(this).apply {
                text = tag.name
                setTextColor(getColor(R.color.accent))
                textSize = 15f
            })
            card.addView(TextView(this).apply {
                text = tag.whenText
                setTextColor(getColor(R.color.muted))
                textSize = 12f
            })
            if (tag.subject.isNotBlank() && tag.subject != tag.name) {
                card.addView(TextView(this).apply {
                    text = tag.subject
                    setTextColor(getColor(R.color.ink))
                    textSize = 13f
                    setPadding(0, dp(4), 0, 0)
                })
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = dp(8)
            list.addView(card, params)
        }
    }

    private fun createRelease() {
        val name = binding.gitPage.releaseName.text?.toString()?.trim().orEmpty()
        val notes = binding.gitPage.releaseNotes.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            AppState.log("Release needs a name")
            return
        }
        AppState.io.execute {
            val text = GitOps.createTag(AppState.cwd, AppState.reposDir, name, notes)
            val tags = runCatching { GitOps.tags(AppState.cwd, AppState.reposDir) }.getOrDefault(emptyList())
            runOnUiThread {
                AppState.log(text)
                renderReleases(tags)
                if (text.startsWith("tagged ")) {
                    binding.gitPage.releaseName.setText("")
                    binding.gitPage.releaseNotes.setText("")
                }
            }
        }
    }

    private fun setupBuild() {
        binding.buildPage.buildApk.setOnClickListener { startBuild() }
        binding.buildPage.reviseBuild.setOnClickListener { reviseBuild() }
        binding.buildPage.installApk.setOnClickListener { installBuiltApk() }
    }

    private fun startBuild() {
        if (AppState.buildBusy || AppState.reviseBusy) return
        saveEditor(announce = false)
        val url = store.buildUrl()
        val token = store.buildToken()
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
                        AppState.buildLog("Set the build URL and token on Console → Variables.")
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
            val message = "Add an API key in Settings, then tap Revise."
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
        binding.filesPage.filesPath.text = AppState.downloadNote ?: RepoFiles.display(cwd, repos)
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
        binding.filesPage.filesEmpty.text = AppState.downloadNote ?: if (cwd.canonicalPath == repos.canonicalPath) {
            "Find a repo on Console, or tap Open for a folder already on this phone."
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
        binding.vibePage.model.setText(store.get(provider, "model", provider.defaultModel))
        binding.vibePage.baseUrl.setText(store.get(provider, "base", provider.defaultBase))
    }

    private fun saveProvider(provider: Provider) {
        store.saveChoice(
            provider,
            binding.vibePage.model.text?.toString()?.trim().orEmpty(),
            binding.vibePage.baseUrl.text?.toString()?.trim().orEmpty()
        )
    }

    private fun sendVibe() {
        if (AppState.vibeBusy) return
        val instruction = binding.vibePage.vibePrompt.text?.toString()?.trim().orEmpty()
        if (instruction.isEmpty()) return
        saveEditor(announce = false)
        saveProvider(currentProvider)
        binding.vibePage.vibePrompt.setText("")
        AppState.chat.add("user" to instruction)
        val provider = currentProvider
        val key = store.get(provider, "key", "")
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
            AppState.log("Add an API key in Settings, then tap Message.")
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
