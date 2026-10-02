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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.andvibe.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity(), UiBridge.Listener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SecretStore
    private var currentProvider = Provider.OPENAI
    private var spinnerReady = false
    private var savedText = ""
    private var editing = false
    private var displayed = emptyList<File>()
    private var gitChanges = emptyList<GitOps.Change>()

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
        setupFiles()
        setupGit()
        setupVibe()
        setupBuild()
        setupNav()
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
            "Paste the Cloud Run URL and token. Build APK uploads a Gradle project and waits for the APK."
        }
        val root = runCatching { AppState.projectRoot() }.getOrNull()
        binding.buildPage.projectLine.text = if (root == null) {
            "No repo yet. Clone one in Console."
        } else {
            "${RepoFiles.display(root, AppState.reposDir)} — ${JsRunner.detect(root)}"
        }
        binding.buildPage.apkPath.text = AppState.lastApk ?: "No APK yet"
        val apkReady = AppState.lastApk?.let { File(it).isFile } == true
        binding.buildPage.installApk.isEnabled = apkReady && !AppState.buildBusy
        binding.buildPage.buildApk.isEnabled = !AppState.buildBusy
        binding.buildPage.buildApk.text = if (AppState.buildBusy) "Building…" else "Build APK"
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
        page.gitChanges.visibility = if (showDetail) View.GONE else View.VISIBLE
        page.gitEmpty.text = when {
            snap == null -> "Open a project from Files."
            !snap.isRepo -> snap.summary
            else -> "No changes"
        }
        page.gitEmpty.visibility = if (!showDetail && (snap == null || snap.changes.isEmpty())) {
            View.VISIBLE
        } else {
            View.GONE
        }
        if (!showDetail) {
            gitChanges = snap?.changes.orEmpty()
            page.gitChanges.adapter = ArrayAdapter(this, R.layout.row_file, gitChanges.map { it.label })
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
        syncBack()
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
        page.gitAccount.setOnClickListener { showGitAccount() }
        page.gitDetailClose.setOnClickListener { closeGitDetail() }
        page.gitChanges.setOnItemClickListener { _, _, position, _ ->
            val change = gitChanges.getOrNull(position) ?: return@setOnItemClickListener
            showChangeMenu(change)
        }
    }

    private fun setupBuild() {
        binding.buildPage.buildUrl.setText(store.buildUrl())
        binding.buildPage.buildToken.setText(store.buildToken())
        binding.buildPage.buildApk.setOnClickListener { startBuild() }
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
        if (AppState.buildBusy) return
        saveEditor(announce = false)
        val url = binding.buildPage.buildUrl.text?.toString()?.trim().orEmpty()
        val token = binding.buildPage.buildToken.text?.toString()?.trim().orEmpty()
        saveBuildServer()
        AppState.buildBusy = true
        AppState.clearBuild()
        val appContext = applicationContext
        AppState.io.execute {
            try {
                val root = AppState.projectRoot()
                AppState.buildLog(RepoFiles.display(root, AppState.reposDir))
                if (File(root, "gradlew").isFile) {
                    if (url.isBlank() || token.isBlank()) {
                        AppState.buildLog("Paste the Cloud Run URL and build token, then press Build APK again.")
                    } else {
                        AppState.buildLog("Gradle project. Sending it to Cloud Run.")
                        val apk = CloudBuild.build(appContext, root, url, token, AppState::buildLog)
                        AppState.lastApk = apk.absolutePath
                        AppState.buildLog("")
                        AppState.buildLog("APK")
                        AppState.buildLog(apk.absolutePath)
                        AppState.buildLog("Tap Install.")
                    }
                } else {
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
                AppState.buildLog("build failed: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                AppState.buildBusy = false
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
            try {
                val root = AppState.projectRoot()
                val edit = AiClient.edit(
                    root, cwd, open, instruction, provider, key, model, base, AppState.history
                )
                val report = buildString {
                    append(edit.report)
                    if (auto && edit.written.isNotEmpty()) {
                        append("\n\n")
                        append(JsRunner.compile(root))
                        append('\n')
                        append(JsRunner.test(root))
                    }
                }
                AppState.vibeResult = report
                AppState.writtenPaths = edit.written.map { it.canonicalPath }
                AppState.log(report)
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                AppState.vibeResult = msg
                AppState.writtenPaths = emptyList()
                AppState.log("vibe error: $msg")
            }             finally {
                AppState.vibeBusy = false
                UiBridge.vibeUpdate()
            }
        }
    }

    private fun syncBack() {
        backCallback.isEnabled = editing || AppState.gitDetail != null
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
            val msg = try {
                block()
            } catch (t: Throwable) {
                t.message ?: t.javaClass.simpleName
            }
            if (!msg.isNullOrBlank()) AppState.log(msg)
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

    private fun showGitAccount() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        fun field(hint: String, value: String, password: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine(true)
            if (password) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            layout.addView(
                this,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val name = field("Name", GitOps.authorName)
        val email = field("Email", GitOps.authorEmail)
        val user = field("HTTPS user", GitOps.remoteUser)
        val token = field("HTTPS token or password", GitOps.remoteToken, password = true)
        AlertDialog.Builder(this)
            .setTitle("Git account")
            .setMessage("Push uses the token as the HTTPS password. GitHub wants a personal access token.")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                GitOps.authorName = name.text.toString().trim().ifBlank { "AndVibe" }
                GitOps.authorEmail = email.text.toString().trim().ifBlank { "andvibe@local" }
                GitOps.remoteUser = user.text.toString().trim()
                GitOps.remoteToken = token.text.toString()
                store.saveGit(GitOps.authorName, GitOps.authorEmail, GitOps.remoteUser, GitOps.remoteToken)
            }
            .setNegativeButton("Cancel", null)
            .show()
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
