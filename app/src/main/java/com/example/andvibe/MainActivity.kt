package com.example.andvibe

import com.example.andvibe.core.GitClient
import com.example.andvibe.tasks.Res
import com.example.andvibe.tasks.Resource
import com.example.andvibe.tasks.TaskRunner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import com.example.andvibe.core.GitOps
import com.example.andvibe.core.JsRunner
import com.example.andvibe.core.RepoFiles

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.andvibe.databinding.ActivityMainBinding
import com.example.andvibe.databinding.RowApkBinding
import com.example.andvibe.databinding.RowCardBinding
import com.example.andvibe.databinding.RowFossBinding
import com.example.andvibe.databinding.RowNewsBinding
import com.example.andvibe.databinding.RowGitCommitBinding
import com.example.andvibe.databinding.RowGitFileBinding
import com.example.andvibe.databinding.RowGitSectionBinding
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PRO_ACCOUNT_URL = "https://andvibe.org/account/"

class MainActivity : AppCompatActivity(), UiBridge.Listener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SecretStore
    private var currentProvider = Provider.OPENAI
    private var spinnerReady = false
    private var savedText = ""
    private var editing = false
    private var displayed = emptyList<File>()
    private val gitCollapsed = mutableSetOf<String>()
    private val vault = mutableMapOf<String, EditText>()
    private val apiKeys = linkedMapOf<Provider, EditText>()
    private val promptFields = linkedMapOf<PromptStore.Kind, EditText>()
    private var settingsOpen = false
    private var workspaceOpen = false
    private var understandShowSource = false
    private var understandRendered = ""
    private var mermaidJs: ByteArray? = null
    private var mentionPopup: ListPopupWindow? = null
    private var mentionEditing = false
    private val mentionNames = mutableListOf<String>()
    private val mentionWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            if (mentionEditing || s == null) return
            refreshMentionSpans(s)
            updateMentionPopup()
        }
    }

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

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        askBatteryExemption()
        renderBackground()
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (settingsOpen) {
                closeSettings()
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
        store = (application as AndVibeApp).graph.secrets
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
        setupSearch()
        setupGit()
        setupVibe()
        setupUnderstand()
        setupBuild()
        setupNav()
        setupUsage()
        val open = AppState.openFile
        if (open != null && open.isFile) openEditor(open) else refreshFileList()
        onLog()
        onVibe()
        onUnderstand()
        onBuild()
        onGit()
        openTabFrom(intent)
        paintBusy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openTabFrom(intent)
    }

    override fun onStart() {
        super.onStart()
        graph.tasks.visible = true
        graph.tasks.resync()
        if (settingsOpen) renderBackground()
    }

    override fun onStop() {
        graph.tasks.visible = false
        super.onStop()
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

    override fun onBusy() {
        if (!::binding.isInitialized) return
        paintBusy()
    }

    private fun paintBusy() {
        val label = AppState.busyLabel()
        val busy = label != null
        binding.busyBar.visibility = if (busy) View.VISIBLE else View.GONE
        binding.busyTrack.visibility = if (busy) View.VISIBLE else View.GONE
        binding.busyLabel.text = label.orEmpty()
        paintConsoleBusy()
        paintFilesBusy()
    }

    private fun paintConsoleBusy() {
        val page = binding.consolePage
        val running = AppState.consoleBusy
        BusyUi.setEnabled(page.commandRun, !running)
        BusyUi.setEnabled(page.commandInput, !running)
    }

    private fun paintFilesBusy() {
        if (editing) return
        BusyUi.setEnabled(binding.filesPage.filesOpen, !(AppState.importBusy || AppState.downloadBusy))
    }

    private fun paintTape() {
        val project = AppState.selectedRoot()?.name ?: "—"
        binding.consolePage.consoleProject.text = project
        binding.consolePage.consoleClock.text = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        paintConsoleBusy()
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
            val steps = synchronized(AppState.agentSteps) {
                AppState.agentSteps.joinToString("\n").also { AppState.agentSteps.clear() }
            }
            if (steps.isNotBlank()) AppState.chat.add("steps" to steps)
            AppState.chat.add("assistant" to AppState.vibeResult)
            AppState.vibeResult = ""
            ChatStore.save()
            ChatStore.sync()
            if (binding.vibePage.historyPane.visibility == View.VISIBLE) renderHistory()
        }
        renderChat()
        renderVibeRepo()
        val send = binding.vibePage.vibeSend
        when {
            !AppState.vibeBusy -> {
                BusyUi.setEnabled(send, true)
                send.text = "Send"
            }
            AppState.agentStop.get() -> {
                BusyUi.setEnabled(send, false)
                send.text = "Stop"
            }
            else -> {
                BusyUi.setEnabled(send, true)
                send.text = "Stop"
            }
        }
        paintBusy()
        val paths = AppState.writtenPaths
        AppState.writtenPaths = emptyList()
        if (paths.isNotEmpty()) {
            reloadOpen(paths)
            if (!editing) refreshFileList()
        }
    }

    override fun onUnderstand() {
        if (!::binding.isInitialized) return
        renderUnderstand()
    }

    override fun onBuild() {
        renderSavedApks()
        renderBuildHistory()
        binding.buildPage.buildScope.text = "WORKSPACE · ${WorkspaceStore.current().name.uppercase(Locale.US)}"
        val scroll = binding.buildPage.buildScroll
        val child = scroll.getChildAt(0)
        val nearBottom = child == null || child.bottom <= scroll.height + scroll.scrollY + 160
        val text = AppState.buildText()
        binding.buildPage.buildLog.text = text
        val page = binding.buildPage
        val root = AppState.selectedRoot()
        page.projectLine.text = if (root == null) {
            "No repo yet. Clone one from Search."
        } else {
            RepoFiles.display(root, AppState.reposDir)
        }
        page.projectKind.text = root?.let { JsRunner.detect(it) }.orEmpty()
        page.projectKind.visibility = if (root == null) View.GONE else View.VISIBLE
        page.apkPath.text = AppState.lastApk?.let { File(it).name } ?: "No APK yet"
        val busy = AppState.buildBusy || AppState.reviseBusy
        val apkReady = AppState.lastApk?.let { File(it).isFile } == true
        val (status, statusColor) = when {
            AppState.buildBusy -> "● BUILDING" to R.color.quote
            AppState.reviseBusy -> "● REVISING" to R.color.quote
            apkReady -> "● APK READY" to R.color.bid
            else -> "● NOT BUILT" to R.color.muted
        }
        page.buildStatus.text = status
        page.buildStatus.setTextColor(getColor(statusColor))
        binding.buildPage.installApk.isEnabled = apkReady
        BusyUi.setEnabled(binding.buildPage.installApk, apkReady && !busy)
        BusyUi.setEnabled(binding.buildPage.buildApk, !busy)
        BusyUi.setEnabled(binding.buildPage.reviseBuild, !busy && text.isNotBlank())
        paintBusy()
        if (nearBottom && text.isNotBlank()) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onGit() {
        val page = binding.gitPage
        val snap = AppState.gitSnapshot
        val showDetail = AppState.gitDetail != null
        page.gitBranch.text = when {
            snap == null || snap.branch.isBlank() -> "No repository"
            else -> snap.branch
        }
        page.gitSync.text = when {
            snap?.isRepo != true -> ""
            !snap.upstream -> "no upstream"
            else -> "↓${snap.behind} ↑${snap.ahead}"
        }
        page.gitSummary.text = when {
            AppState.gitBusy -> "Working…"
            snap == null -> "Open a project, then refresh."
            !snap.isRepo -> snap.summary
            else -> snap.remote.ifBlank { "No remote" }
        }
        val staged = snap?.changes?.count { it.staged } ?: 0
        page.gitMessage.hint = if (snap?.isRepo == true && snap.branch.isNotBlank()) {
            "Message (commit on ${snap.branch})"
        } else {
            "Message"
        }
        page.gitCommit.text = if (staged > 0) "Commit $staged" else "Commit"
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
            !snap.isRepo -> "${snap.summary}\n\nUse ⋯ › Initialize repository to start tracking."
            else -> "No changes"
        }
        page.gitEmpty.visibility = if (!showDetail && !showLists) View.VISIBLE else View.GONE
        if (showLists && snap != null) {
            val y = page.gitScroll.scrollY
            fillGitLists(page.gitLists, snap)
            page.gitScroll.post { page.gitScroll.scrollTo(0, y) }
        }
        val enabled = !AppState.gitBusy
        for (button in listOf(page.gitRefresh, page.gitPull, page.gitPush, page.gitMore)) {
            BusyUi.setEnabled(button, enabled)
        }
        BusyUi.setEnabled(page.gitBranch, enabled)
        BusyUi.setEnabled(page.gitCommit, enabled)
        BusyUi.setEnabled(page.gitSuggest, enabled && snap?.isRepo == true && snap.changes.isNotEmpty())
        BusyUi.setEnabled(page.createRelease, enabled)
        BusyUi.setEnabled(page.releaseName, enabled)
        BusyUi.setEnabled(page.releaseNotes, enabled)
        paintBusy()
        syncBack()
    }

    override fun onMcp() {
        if (!::binding.isInitialized) return
        renderMcp()
    }

    private fun renderMcp() {
        val page = binding.settingsPage
        val on = DebugMcp.isEnabled()
        if (page.mcpEnabled.isChecked != on) page.mcpEnabled.isChecked = on
        page.mcpStatus.text = DebugMcp.statusText()
        page.mcpRetry.visibility = if (on) View.VISIBLE else View.GONE
    }

    override fun onUsage() {
        if (!::binding.isInitialized) return
        val ws = WorkspaceStore.current()
        binding.openWorkspace.text = ws.name
        paintProject()
        binding.usageTokens.text = (ws.inputTokens + ws.outputTokens).toString()
        binding.usagePrice.text = WorkspaceStore.priceText(ws.costMicros)
        if (AppState.tab == AppState.Tab.BOARD) renderBoard()
        if (ChatStore.sync()) {
            renderChat()
            renderHistory()
        }
    }

    private fun paintProject() {
        if (!::binding.isInitialized) return
        val name = AppState.selectedRoot()?.name
        binding.openProject.text = name ?: "—"
        binding.openProject.setTextColor(getColor(if (name == null) R.color.muted else R.color.accent))
    }

    override fun onScreenshot(tab: AppState.Tab?, done: (Bitmap?) -> Unit) {
        if (!::binding.isInitialized || isFinishing) {
            done(null)
            return
        }
        if (tab != null && (tab != AppState.tab || settingsOpen || workspaceOpen)) showTab(tab)
        val root = window.decorView
        root.postDelayed({ captureWindow(root, done) }, if (tab == null) 50L else 400L)
    }

    private fun captureWindow(root: View, done: (Bitmap?) -> Unit) {
        if (root.width <= 0 || root.height <= 0) {
            done(null)
            return
        }
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PixelCopy.request(window, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) done(bitmap) else done(drawWindow(root, bitmap))
            }, Handler(Looper.getMainLooper()))
        } else {
            done(drawWindow(root, bitmap))
        }
    }

    private fun drawWindow(root: View, bitmap: Bitmap): Bitmap {
        val canvas = Canvas(bitmap)
        canvas.drawColor(getColor(R.color.bg))
        root.draw(canvas)
        return bitmap
    }

    override fun onProject() {
        saveEditor(announce = false)
        editing = false
        AppState.openFile = null
        savedText = ""
        binding.filesPage.editor.setText("")
        refreshFileList()
        if (AppState.tab == AppState.Tab.GIT) refreshGit() else onGit()
        if (AppState.tab == AppState.Tab.BUILD) onBuild()
        if (!AppState.understandBusy) {
            AppState.understandText = ""
            AppState.understandNote = ""
            understandRendered = ""
            understandShowSource = false
            maybeLoadUnderstandDoc()
        }
        if (AppState.tab == AppState.Tab.UNDERSTAND) onUnderstand() else renderUnderstandRepo()
        renderVibeRepo()
        paintTape()
        paintProject()
        if (workspaceOpen) renderRepoChecks()
    }

    private fun setupSettings() {
        binding.consolePage.openSettings.setOnClickListener { openSettings() }
        binding.settingsPage.mcpRetry.setOnClickListener {
            DebugMcp.start()
            renderMcp()
        }
        renderMcp()
        binding.settingsPage.mcpEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked != DebugMcp.isEnabled()) DebugMcp.setEnabled(this, checked)
            renderMcp()
        }
        binding.settingsPage.allowBackground.setOnClickListener { requestBackground() }
        binding.settingsPage.saveGit.setOnClickListener { saveGitSettings() }
        binding.settingsPage.saveApiKeys.setOnClickListener { saveApiKeys(announce = true) }
        binding.settingsPage.savePrompts.setOnClickListener { savePrompts(announce = true) }
        binding.settingsPage.resetPrompts.setOnClickListener { resetAllPrompts() }
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
        buildPromptFields()
    }

    private fun openSettings() {
        if (workspaceOpen) closeWorkspace()
        settingsOpen = true
        renderMcp()
        renderBackground()
        loadGitSettings()
        loadApiKeys()
        loadPrompts()
        binding.settingsPage.root.visibility = View.VISIBLE
        syncBack()
    }

    private val graph get() = (application as AndVibeApp).graph

    private fun launchTask(
        label: String,
        tab: AppState.Tab,
        holds: Set<Resource>,
        track: Boolean = true,
        lane: CoroutineDispatcher? = null,
        block: suspend () -> TaskRunner.Done?,
    ): TaskRunner.Task? {
        if (track) {
            val prefs = getSharedPreferences("andvibe_background", MODE_PRIVATE)
            if (!prefs.getBoolean("asked", false)) {
                prefs.edit().putBoolean("asked", true).apply()
                requestBackground()
            }
        }
        return graph.tasks.launch(label, tab, holds, lane ?: graph.dispatchers.repo, track, block)
    }

    private fun requestBackground() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!Notify.allowed(this)) {
            openNotificationSettings()
            return
        }
        askBatteryExemption()
    }

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        runCatching { startActivity(intent) }
    }

    private fun batteryExempt(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    @Suppress("BatteryLife")
    private fun askBatteryExemption() {
        if (batteryExempt()) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun renderBackground() {
        val notify = if (Notify.allowed(this)) "on" else "off"
        val battery = if (batteryExempt()) "unrestricted" else "optimized"
        binding.settingsPage.backgroundStatus.text = "notifications  $notify\nbattery        $battery"
        binding.settingsPage.allowBackground.visibility =
            if (notify == "on" && batteryExempt()) View.GONE else View.VISIBLE
    }

    private fun openTabFrom(intent: Intent?) {
        val name = intent?.getStringExtra(Notify.EXTRA_TAB) ?: return
        intent.removeExtra(Notify.EXTRA_TAB)
        val tab = AppState.Tab.entries.firstOrNull { it.name == name } ?: return
        showTab(tab)
    }

    private fun closeSettings() {
        if (!settingsOpen) return
        saveApiKeys(announce = false)
        savePrompts(announce = false)
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

    private fun buildPromptFields() {
        val parent = binding.settingsPage.promptList
        if (parent.childCount > 0) return
        for (kind in PromptStore.Kind.entries) {
            val title = TextView(this).apply {
                text = kind.label
                setTextColor(getColor(R.color.ink))
                textSize = 13f
            }
            val titleParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            titleParams.topMargin = dp(16)
            parent.addView(title, titleParams)

            val blurb = TextView(this).apply {
                text = kind.blurb
                setTextColor(getColor(R.color.muted))
                textSize = 12f
            }
            val blurbParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            blurbParams.topMargin = dp(4)
            parent.addView(blurb, blurbParams)

            val field = EditText(this).apply {
                setHintTextColor(getColor(R.color.muted))
                setTextColor(getColor(R.color.ink))
                setBackgroundResource(R.drawable.bg_field)
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                gravity = Gravity.TOP or Gravity.START
                minLines = if (kind == PromptStore.Kind.COMMIT) 2 else 6
                setPadding(dp(10), dp(10), dp(10), dp(10))
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setText(PromptStore.get(kind))
            }
            promptFields[kind] = field
            val fieldParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            fieldParams.topMargin = dp(8)
            parent.addView(field, fieldParams)

            val reset = Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Reset"
                textSize = 13f
                minHeight = dp(40)
                setOnClickListener {
                    field.setText(PromptStore.default(kind))
                    PromptStore.reset(kind)
                    binding.settingsPage.promptNote.text = "Restored ${kind.label} default."
                }
            }
            val resetParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            resetParams.topMargin = dp(6)
            parent.addView(reset, resetParams)
        }
    }

    private fun loadPrompts() {
        for ((kind, field) in promptFields) {
            field.setText(PromptStore.get(kind))
        }
        binding.settingsPage.promptNote.text = ""
    }

    private fun savePrompts(announce: Boolean) {
        if (promptFields.isEmpty()) return
        for ((kind, field) in promptFields) {
            PromptStore.set(kind, field.text?.toString().orEmpty())
        }
        if (announce) binding.settingsPage.promptNote.text = "Saved."
    }

    private fun resetAllPrompts() {
        PromptStore.resetAll()
        for ((kind, field) in promptFields) {
            field.setText(PromptStore.default(kind))
        }
        binding.settingsPage.promptNote.text = "Restored all defaults."
    }

    private fun setupUsage() {
        binding.openWorkspaceBox.setOnClickListener {
            if (workspaceOpen) closeWorkspace() else openWorkspace()
        }
        binding.openProjectBox.setOnClickListener { showProjects() }
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
        binding.boardPage.addCompleted.setOnClickListener { editCard(null, WorkspaceStore.Column.COMPLETED.id) }
        onUsage()
    }

    private fun openWorkspace() {
        if (settingsOpen) closeSettings()
        workspaceOpen = true
        renderWorkspace()
        binding.workspacePage.root.visibility = View.VISIBLE
        binding.openWorkspaceBox.setBackgroundColor(getColor(R.color.line))
        syncBack()
    }

    private fun closeWorkspace() {
        if (!workspaceOpen) return
        saveWorkspaceName()
        workspaceOpen = false
        binding.workspacePage.root.visibility = View.GONE
        binding.openWorkspaceBox.setBackgroundResource(selectableBackground())
        onUsage()
        syncBack()
    }

    private fun selectableBackground(): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return value.resourceId
    }

    private fun saveWorkspaceName() {
        if (!workspaceOpen || !::binding.isInitialized) return
        WorkspaceStore.rename(binding.workspacePage.workspaceName.text?.toString().orEmpty())
    }

    private fun renderWorkspace() {
        val ws = WorkspaceStore.current()
        binding.workspacePage.workspaceName.setText(ws.name)
        binding.workspacePage.deleteWorkspace.isEnabled = WorkspaceStore.workspaces().size > 1
        renderRepoChecks()
        renderWorkspaces()
    }

    private fun renderRepoChecks() {
        val ws = WorkspaceStore.current()
        val names = WorkspaceStore.downloaded()
        binding.workspacePage.repoEmpty.visibility = if (names.isEmpty()) View.VISIBLE else View.GONE
        val repos = binding.workspacePage.repoList
        repos.removeAllViews()
        for (name in names) {
            val box = MaterialCheckBox(this).apply {
                text = name
                isChecked = name in ws.repos
                setOnCheckedChangeListener { _, checked ->
                    WorkspaceStore.setRepo(name, checked)
                    if (!AppState.fitWorkspace()) {
                        refreshFileList()
                        renderVibeRepo()
                    }
                }
            }
            repos.addView(box)
        }
    }

    private fun canSwitchWorkspace(): Boolean {
        if (!AppState.workBusy()) return true
        android.widget.Toast.makeText(
            this, "Wait for the agent or build to finish before switching workspaces", android.widget.Toast.LENGTH_SHORT
        ).show()
        return false
    }

    private fun switchedWorkspace() {
        if (editing) closeEditor(save = true)
        AppState.fitWorkspace(toRoot = true)
        AppState.loadWorkspaceBuild()
        shownApks = ""
        renderWorkspace()
        onUsage()
        refreshFileList()
        renderVibeRepo()
        onBuild()
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
                if (ws.id == current.id || !canSwitchWorkspace()) return@setOnClickListener
                saveWorkspaceName()
                if (WorkspaceStore.select(ws.id)) switchedWorkspace()
            }
            list.addView(row)
        }
    }

    private fun promptNewWorkspace() {
        if (!canSwitchWorkspace()) return
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
                switchedWorkspace()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteWorkspace() {
        val ws = WorkspaceStore.current()
        if (WorkspaceStore.workspaces().size <= 1 || !canSwitchWorkspace()) return
        AlertDialog.Builder(this)
            .setTitle(ws.name)
            .setMessage("Delete this workspace with its board, chats, saved APKs, and build history. The repos stay on the phone.")
            .setPositiveButton("Delete") { _, _ ->
                if (WorkspaceStore.delete(ws.id)) {
                    ChatStore.forget(ws.id)
                    ApkLibrary.forget(this, ws.id)
                    BuildHistory.forget(ws.id)
                }
                switchedWorkspace()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderBoard() {
        val ws = WorkspaceStore.current()
        binding.boardPage.boardScope.text = "WORKSPACE · ${ws.name.uppercase(Locale.US)}"
        fillColumn(binding.boardPage.ideaCards, binding.boardPage.ideaEmpty, WorkspaceStore.Column.IDEA)
        fillColumn(binding.boardPage.bugCards, binding.boardPage.bugEmpty, WorkspaceStore.Column.BUG)
        fillColumn(binding.boardPage.solutionCards, binding.boardPage.solutionEmpty, WorkspaceStore.Column.SOLUTION)
        fillColumn(binding.boardPage.completedCards, binding.boardPage.completedEmpty, WorkspaceStore.Column.COMPLETED)
    }

    private fun fillColumn(parent: LinearLayout, empty: android.widget.TextView, column: WorkspaceStore.Column) {
        parent.removeAllViews()
        val cards = WorkspaceStore.cards(column)
        empty.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
        val done = column == WorkspaceStore.Column.COMPLETED
        for (card in cards) {
            val row = RowCardBinding.inflate(layoutInflater, parent, false)
            row.cardTitle.text = card.title
            if (card.body.isBlank()) {
                row.cardBody.visibility = View.GONE
            } else {
                row.cardBody.visibility = View.VISIBLE
                row.cardBody.text = card.body
            }
            if (done) {
                row.root.setOnClickListener { showCardMenu(card) }
            } else {
                row.root.setOnClickListener { buildCard(card) }
                row.root.setOnLongClickListener {
                    showCardMenu(card)
                    true
                }
            }
            parent.addView(row.root)
        }
    }

    private fun showCardMenu(card: WorkspaceStore.Card) {
        val done = card.column == WorkspaceStore.Column.COMPLETED.id
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (!done) {
            labels.add("Build")
            actions.add { buildCard(card) }
        }
        labels.add("Edit")
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

    private fun buildCard(card: WorkspaceStore.Card) {
        if (AppState.vibeBusy) {
            android.widget.Toast.makeText(
                this, "Wait for the agent to finish", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        val instruction = cardInstruction(card)
        if (card.column != WorkspaceStore.Column.COMPLETED.id) {
            WorkspaceStore.moveCard(card.id, WorkspaceStore.Column.COMPLETED.id)
            renderBoard()
        }
        startVibe(instruction)
    }

    private fun cardInstruction(card: WorkspaceStore.Card): String {
        val lead = when (card.column) {
            WorkspaceStore.Column.BUG.id -> "Fix this bug from the Board."
            WorkspaceStore.Column.SOLUTION.id -> "Implement this solution from the Board."
            WorkspaceStore.Column.COMPLETED.id -> "Revisit this completed Board item."
            else -> "Implement this feature from the Board."
        }
        return buildString {
            append(lead)
            append("\n\n")
            append(card.title.trim())
            val note = card.body.trim()
            if (note.isNotEmpty()) {
                append("\n\n")
                append(note)
            }
            append("\n\nMake a focused change in the open repo. Do not commit.")
        }
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
        buildVault()
    }

    private fun setupSearch() {
        val page = binding.searchPage
        page.findGo.setOnClickListener { findRepos() }
        page.findQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                findRepos()
                true
            } else {
                false
            }
        }
        renderFind()
        ensureFossFeed()
        page.searchTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                AppState.findNewsTab = tab.position == 1
                page.findQuery.hint = if (AppState.findNewsTab) "Latest technology" else "What kind of project?"
                if (AppState.findNewsTab) ensureFossFeed()
                renderFind()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun ensureFossFeed() {
        if (!FossFeed.stale(this) || AppState.feedBusy) return
        if (AppState.findBrief.isBlank()) {
            AppState.findNote = "Refreshing the daily FOSS cache…"
            if (AppState.tab == AppState.Tab.SEARCH) renderFind()
        }
        launchTask("Refreshing feed", AppState.Tab.SEARCH, setOf(Res.FEED), track = false) {
            val note = runCatching { FossFeed.refresh(applicationContext) }.getOrElse {
                it.message ?: "cache failed"
            }
            if (AppState.findBrief.isBlank()) AppState.findNote = note
            runOnUiThread {
                if (!isFinishing && AppState.tab == AppState.Tab.SEARCH) renderFind()
            }
            null
        }
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
        vaultLink(secrets, "No Cloud Run of your own? Get a Pro build key at andvibe.org/account", PRO_ACCOUNT_URL)
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

    private fun vaultLink(parent: LinearLayout, text: String, url: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.accent))
            textSize = 13f
            setPadding(0, dp(6), 0, dp(2))
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
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
        binding.vibePage.model.setText(vaultText("${selected.id}_model"))
        binding.vibePage.baseUrl.setText(vaultText("${selected.id}_base"))
        binding.settingsPage.gitName.setText(name)
        binding.settingsPage.gitEmail.setText(email)
        binding.settingsPage.gitHttpsUser.setText(user)
        binding.settingsPage.gitOrigin.setText(origin)
        graph.scope.launch(graph.dispatchers.repo) {
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
        if (AppState.consoleBusy) return
        val line = binding.consolePage.commandInput.text?.toString()?.trim().orEmpty()
        if (line.isEmpty()) return
        binding.consolePage.commandInput.setText("")
        AppState.log("$ $line")
        val parts = Console.tokenize(line)
        val slow = parts.getOrNull(0) == "git" && parts.getOrNull(1) in setOf("clone", "push", "pull", "fetch")
        val label = if (slow) parts.take(2).joinToString(" ") else "Running command"
        launchTask(label, AppState.Tab.CONSOLE, setOf(Res.CONSOLE), track = slow) {
            try {
                Console.run(line)
                if (slow) TaskRunner.Done("$label finished", line) else null
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                AppState.log("error: $msg")
                if (slow) TaskRunner.Done("$label failed", msg) else null
            }
        }
        paintBusy()
    }

    private fun findRepos() {
        if (AppState.findBusy) return
        val query = binding.searchPage.findQuery.text?.toString()?.trim().orEmpty().ifBlank {
            if (AppState.findNewsTab) "latest technology and open-source software in the news" else ""
        }
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
        AppState.findHits = emptyList()
        AppState.findNews = emptyList()
        AppState.findBrief = ""
        AppState.findNote = "Searching the web, then checking GitHub, GitLab, Codeberg, and SourceHut…"
        DebugLog.step("find", "start chars=${query.length} provider=${provider.id}")
        launchTask("Searching", AppState.Tab.SEARCH, setOf(Res.FIND), track = false) {
            val result = try {
                FossSearch.search(query, provider, key, model, base, FossFeed.matching(this, query))
            } catch (t: Throwable) {
                DebugLog.step("find", "fail ${t.javaClass.simpleName}: ${t.message}")
                FossSearch.SearchResult(emptyList(), emptyList(), "", t.message ?: "search failed")
            }
            AppState.findHits = result.hits
            AppState.findNews = result.news
            AppState.findBrief = result.brief
            AppState.findNote = result.note
            DebugLog.step("find", "done hits=${result.hits.size}")
            runOnUiThread {
                if (!isFinishing) renderFind()
            }
            null
        }
        renderFind()
    }

    private fun renderFind() {
        val page = binding.searchPage
        BusyUi.setEnabled(page.findGo, !AppState.findBusy)
        val note = AppState.findNote
        val model = store.get(currentProvider, "model", currentProvider.defaultModel).ifBlank { currentProvider.defaultModel }
        page.findStatus.text = when {
            AppState.findBrief.isNotBlank() -> AppState.findBrief
            AppState.findNewsTab -> AppState.findNote.ifBlank { FossFeed.status(this) }
            note.isNotBlank() -> note
            else -> "${currentProvider.label} · $model"
        }
        page.findStatus.visibility = View.VISIBLE
        page.findScroll.visibility = View.VISIBLE
        page.findResults.removeAllViews()
        val cloneOk = !AppState.downloadBusy && !AppState.findBusy
        if (AppState.findNewsTab) {
            val cached = FossFeed.projects(this)
            if (cached.isNotEmpty()) {
                page.findResults.addView(sectionLabel("This week"))
                for (hit in cached) page.findResults.addView(repoRow(hit, cloneOk))
            }
            var group = ""
            for (channel in FossFeed.channels) {
                if (channel.group != group) {
                    group = channel.group
                    page.findResults.addView(sectionLabel(group))
                }
                val row = RowNewsBinding.inflate(layoutInflater, page.findResults, false)
                row.newsTitle.text = channel.name
                row.newsMeta.text = channel.detail
                row.newsSummary.visibility = View.GONE
                row.root.setOnClickListener { openNews(channel.url) }
                page.findResults.addView(row.root)
            }
            if (AppState.findNews.isNotEmpty()) {
                page.findResults.addView(sectionLabel("From the web"))
                for (story in AppState.findNews) {
                    val row = RowNewsBinding.inflate(layoutInflater, page.findResults, false)
                    row.newsTitle.text = story.title
                    row.newsMeta.text = story.source.ifBlank { hostOf(story.url) }
                    row.newsSummary.text = story.summary
                    row.root.setOnClickListener { openNews(story.url) }
                    val repo = story.repo
                    if (repo != null) {
                        row.newsClone.visibility = View.VISIBLE
                        row.newsClone.text = "Clone ${repo.name}"
                        BusyUi.setEnabled(row.newsClone, cloneOk)
                        row.newsClone.setOnClickListener { if (cloneOk) downloadHit(repo) }
                    }
                    page.findResults.addView(row.root)
                }
            }
        } else {
            for (hit in AppState.findHits) page.findResults.addView(repoRow(hit, cloneOk))
        }
    }

    private fun sectionLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.muted))
            textSize = 12f
            setPadding(0, dp(14), 0, dp(4))
        }
    }

    private fun repoRow(hit: FossSearch.RepoHit, cloneOk: Boolean = true): View {
        val row = RowFossBinding.inflate(layoutInflater, null, false)
        row.fossName.text = hit.name
        val source = hit.why
        val fromCache = source == "Codeberg" || source == "GitLab" ||
            source.startsWith("GitHub") || source.startsWith("Topic:")
        row.fossMeta.text = when {
            fromCache && hit.stars > 0 -> "$source · ${hit.stars} stars"
            fromCache -> source
            hit.stars > 0 -> "${hostOf(hit.page)} · ${hit.stars} stars"
            else -> hostOf(hit.page)
        }
        row.fossWhy.text = if (fromCache) hit.blurb else hit.why.ifBlank { hit.blurb }
        BusyUi.setEnabled(row.root, cloneOk)
        row.root.setOnClickListener { if (cloneOk) downloadHit(hit) }
        return row.root
    }

    private fun openNews(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            AppState.findNote = t.message ?: "could not open the story"
            renderFind()
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
            "git.sr.ht" -> "SourceHut"
            else -> host ?: "repo"
        }
    }

    private fun downloadHit(hit: FossSearch.RepoHit) {
        if (AppState.downloadBusy || AppState.importBusy) return
        if (editing) closeEditor(save = true)
        AppState.downloadNote = "Downloading ${hit.name}…"
        saveEditor(announce = false)
        binding.bottomNav.selectedItemId = R.id.nav_files
        refreshFileList()
        paintBusy()
        AppState.log("clone ${hit.cloneUrl}")
        val app = applicationContext
        launchTask("Cloning ${hit.name}", AppState.Tab.FILES, setOf(Res.DOWNLOAD)) {
            var failed: String? = null
            try {
                val url = GitClient.normalizeGitUrl(hit.cloneUrl)
                val name = GitClient.repoNameFromUrl(url)
                val dest = File(AppState.reposDir, name)
                if (dest.isDirectory && !dest.list().isNullOrEmpty()) {
                    WorkspaceStore.include(name)
                    AppState.cwd = dest.canonicalFile
                    ProjectStore.remember(app, AppState.cwd)
                    AppState.log("already in ~/$name")
                } else {
                    GitClient.clone(url, dest, AppState::log)
                    WorkspaceStore.include(name)
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
                AppState.downloadNote = null
                UiBridge.filesChanged()
            }
            failed?.let { message ->
                runOnUiThread {
                    if (!isFinishing && !editing) binding.filesPage.filesPath.text = message
                }
            }
            if (failed == null) TaskRunner.Done("Clone finished", "${hit.name} is open in Files.")
            else TaskRunner.Done("Clone failed", failed.orEmpty())
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
        binding.vibePage.vibeRepoPick.setOnClickListener { pickVibeRepo() }
        binding.vibePage.vibeRepoBar.setOnClickListener { pickVibeRepo() }
        binding.vibePage.vibeMention.setOnClickListener { openMentionPicker(force = true) }
        binding.vibePage.vibePrompt.addTextChangedListener(mentionWatcher)
        binding.vibePage.newChat.setOnClickListener {
            if (AppState.vibeBusy) return@setOnClickListener
            ChatStore.startNew()
            renderChat()
            binding.vibePage.vibeTabs.getTabAt(0)?.select()
        }
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

    private fun mentionColors(): Triple<Int, Int, Int> {
        val accent = getColor(R.color.accent)
        val fill = ColorUtils.setAlphaComponent(accent, 0x2A)
        return Triple(fill, accent, accent)
    }

    private fun refreshMentionSpans(text: Editable) {
        val known = WorkspaceStore.activeRepos().map { it.name }
        val (fill, stroke, color) = mentionColors()
        mentionEditing = true
        try {
            ProjectMentions.applySpans(text, known, fill, stroke, color)
        } finally {
            mentionEditing = false
        }
    }

    private fun updateMentionPopup() {
        val prompt = binding.vibePage.vibePrompt
        val query = ProjectMentions.atQuery(prompt.text ?: "", prompt.selectionStart)
        if (query == null) {
            mentionPopup?.dismiss()
            return
        }
        showMentionChoices(query.query, query.start, query.start + 1 + query.query.length)
    }

    private fun openMentionPicker(force: Boolean) {
        if (AppState.vibeBusy) return
        val others = ProjectMentions.filterRepos(
            WorkspaceStore.activeRepos(),
            "",
            exclude = vibeRoot()?.name
        )
        if (others.isEmpty()) {
            if (WorkspaceStore.activeRepos().isEmpty()) openWorkspace()
            else AppState.log("No other projects in this workspace to reference")
            return
        }
        if (!force) {
            updateMentionPopup()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Reference project")
            .setItems(others.map { it.name }.toTypedArray()) { _, which ->
                val prompt = binding.vibePage.vibePrompt
                val cursor = prompt.selectionStart.coerceAtLeast(0)
                val q = ProjectMentions.atQuery(prompt.text ?: "", cursor)
                if (q != null) {
                    insertMention(others[which].name, q.start, cursor)
                } else {
                    insertMention(others[which].name, cursor, cursor)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMentionChoices(filter: String, replaceStart: Int, replaceEnd: Int) {
        val dirs = ProjectMentions.filterRepos(
            WorkspaceStore.activeRepos(),
            filter,
            exclude = vibeRoot()?.name
        )
        if (dirs.isEmpty()) {
            mentionPopup?.dismiss()
            return
        }
        mentionNames.clear()
        mentionNames.addAll(dirs.map { it.name })
        val prompt = binding.vibePage.vibePrompt
        val popup = mentionPopup ?: ListPopupWindow(this).also {
            it.anchorView = prompt
            it.isModal = false
            mentionPopup = it
        }
        popup.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, mentionNames)
        )
        popup.setOnItemClickListener { _, _, position, _ ->
            val name = mentionNames.getOrNull(position) ?: return@setOnItemClickListener
            val q = ProjectMentions.atQuery(prompt.text ?: "", prompt.selectionStart)
            val start = q?.start ?: replaceStart
            val end = prompt.selectionStart.coerceAtLeast(start)
            insertMention(name, start, end)
        }
        popup.width = prompt.width.coerceAtLeast(280)
        popup.height = ListPopupWindow.WRAP_CONTENT
        if (!popup.isShowing) popup.show()
    }

    private fun insertMention(name: String, replaceStart: Int, replaceEnd: Int) {
        mentionPopup?.dismiss()
        val prompt = binding.vibePage.vibePrompt
        val known = WorkspaceStore.activeRepos().map { it.name }
        val (fill, stroke, color) = mentionColors()
        mentionEditing = true
        try {
            val (spanned, cursor) = ProjectMentions.insert(
                prompt.text ?: "",
                replaceStart,
                replaceEnd,
                name,
                known,
                fill,
                stroke,
                color
            )
            prompt.removeTextChangedListener(mentionWatcher)
            prompt.setText(spanned)
            prompt.setSelection(cursor.coerceIn(0, spanned.length))
            prompt.addTextChangedListener(mentionWatcher)
        } finally {
            mentionEditing = false
        }
        prompt.requestFocus()
    }

    private fun vibeRoot(): File? = AppState.selectedRoot()

    private fun ensureProject(root: File) {
        val open = runCatching { AppState.projectRoot() }.getOrNull()
        if (open?.canonicalFile != root.canonicalFile) openProject(root)
    }

    private fun renderVibeRepo() {
        val page = binding.vibePage
        val repo = if (AppState.vibeBusy) AppState.vibeRepo else vibeRoot()
        page.vibeRepo.text = repo?.name ?: "No repo selected"
        page.vibeRepo.setTextColor(getColor(if (repo == null) R.color.muted else R.color.accent))
        BusyUi.setEnabled(page.vibeRepoPick, !AppState.vibeBusy)
        BusyUi.setEnabled(page.vibeRepoBar, !AppState.vibeBusy)
        BusyUi.setEnabled(page.vibeMention, !AppState.vibeBusy)
        BusyUi.setEnabled(page.provider, !AppState.vibeBusy)
        BusyUi.setEnabled(page.vibePrompt, !AppState.vibeBusy)
        BusyUi.setEnabled(page.autoTest, !AppState.vibeBusy)
        page.vibePrompt.hint = if (repo == null) {
            "Pick a project, or ask for a new one · @ to reference"
        } else {
            "Message ${repo.name} · @ to reference"
        }
    }

    private fun pickVibeRepo() {
        if (AppState.vibeBusy) return
        val dirs = WorkspaceStore.activeRepos()
        if (dirs.isEmpty()) {
            openWorkspace()
            return
        }
        val current = vibeRoot()?.name
        AlertDialog.Builder(this)
            .setTitle("Repo for Vibe")
            .setSingleChoiceItems(dirs.map { it.name }.toTypedArray(), dirs.indexOfFirst { it.name == current }) { dialog, which ->
                dialog.dismiss()
                if (dirs[which].name != current) openProject(dirs[which])
            }
            .setNeutralButton("Edit repos") { _, _ -> openWorkspace() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupUnderstand() {
        val page = binding.understandPage
        page.understandRun.setOnClickListener { runUnderstand() }
        page.understandSave.setOnClickListener { saveUnderstand() }
        page.understandSource.setOnClickListener {
            if (AppState.understandText.isBlank()) return@setOnClickListener
            understandShowSource = !understandShowSource
            renderUnderstand()
        }
        page.understandRepoPick.setOnClickListener { pickUnderstandRepo() }
        page.understandRepoBar.setOnClickListener { pickUnderstandRepo() }
        setupUnderstandWeb(page.understandWeb)
        maybeLoadUnderstandDoc()
    }

    private fun setupUnderstandWeb(web: WebView) {
        web.setBackgroundColor(getColor(R.color.bg))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        web.settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        web.settings.allowUniversalAccessFromFileURLs = false
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                interceptUnderstandAsset(request)
        }
    }

    private fun interceptUnderstandAsset(request: WebResourceRequest): android.webkit.WebResourceResponse? {
        val uri = request.url ?: return null
        if (uri.host != UnderstandDoc.HOST) return null
        val name = uri.lastPathSegment ?: return UnderstandDoc.missing()
        if (name != "mermaid.min.js") return UnderstandDoc.missing()
        val bytes = mermaidJs ?: runCatching {
            assets.open("understand/mermaid.min.js").use { it.readBytes() }
        }.getOrNull()?.also { mermaidJs = it } ?: return UnderstandDoc.missing()
        return UnderstandDoc.asset(name, bytes, "application/javascript")
    }

    private fun renderUnderstandRepo() {
        val page = binding.understandPage
        val repo = if (AppState.understandBusy) AppState.understandRepo else vibeRoot()
        page.understandRepo.text = repo?.name ?: "No repo selected"
        page.understandRepo.setTextColor(getColor(if (repo == null) R.color.muted else R.color.accent))
        BusyUi.setEnabled(page.understandRepoPick, !AppState.understandBusy)
        BusyUi.setEnabled(page.understandRepoBar, !AppState.understandBusy)
    }

    private fun pickUnderstandRepo() {
        if (AppState.understandBusy) return
        val dirs = WorkspaceStore.activeRepos()
        if (dirs.isEmpty()) {
            openWorkspace()
            return
        }
        val current = vibeRoot()?.name
        AlertDialog.Builder(this)
            .setTitle("Repo to understand")
            .setSingleChoiceItems(dirs.map { it.name }.toTypedArray(), dirs.indexOfFirst { it.name == current }) { dialog, which ->
                dialog.dismiss()
                if (dirs[which].name != current) {
                    openProject(dirs[which])
                    AppState.understandText = ""
                    AppState.understandNote = ""
                    understandRendered = ""
                    understandShowSource = false
                    maybeLoadUnderstandDoc()
                    renderUnderstand()
                }
            }
            .setNeutralButton("Edit repos") { _, _ -> openWorkspace() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun maybeLoadUnderstandDoc() {
        if (AppState.understandBusy || AppState.understandText.isNotBlank()) return
        val root = vibeRoot() ?: return
        val saved = Understand.loadSaved(root) ?: return
        AppState.understandText = saved
        AppState.understandNote = "Loaded UNDERSTAND.md"
    }

    private fun renderUnderstand() {
        val page = binding.understandPage
        renderUnderstandRepo()
        val text = AppState.understandText
        val has = text.isNotBlank()
        page.understandEmpty.visibility = if (has) View.GONE else View.VISIBLE
        page.understandSave.isEnabled = has
        BusyUi.setEnabled(page.understandSave, has && !AppState.understandBusy)
        page.understandSource.isEnabled = has
        page.understandSource.text = if (understandShowSource) "View" else "Source"
        BusyUi.setEnabled(page.understandFocus, !AppState.understandBusy)
        if (!has) {
            understandRendered = ""
            page.understandWeb.visibility = View.GONE
            page.understandScroll.visibility = View.GONE
            page.understandOut.text = ""
            if (page.understandWeb.url != null) page.understandWeb.loadUrl("about:blank")
        } else if (understandShowSource) {
            page.understandWeb.visibility = View.GONE
            page.understandScroll.visibility = View.VISIBLE
            page.understandOut.text = text
        } else {
            page.understandScroll.visibility = View.GONE
            page.understandWeb.visibility = View.VISIBLE
            if (text != understandRendered) {
                understandRendered = text
                page.understandWeb.loadDataWithBaseURL(
                    "https://${UnderstandDoc.HOST}/",
                    UnderstandDoc.page(text),
                    "text/html",
                    "utf-8",
                    null
                )
            }
        }
        when {
            !AppState.understandBusy -> {
                BusyUi.setEnabled(page.understandRun, true)
                page.understandRun.text = "Understand"
                page.understandStatus.text = when {
                    AppState.understandNote.isNotBlank() -> "● ${AppState.understandNote.uppercase(Locale.US)}"
                    has -> "● READY"
                    else -> "● READY"
                }
                page.understandStatus.setTextColor(
                    getColor(if (AppState.understandNote.contains("fail", true) ||
                        AppState.understandNote.contains("error", true)
                    ) R.color.quote else R.color.muted)
                )
            }
            AppState.understandStop.get() -> {
                BusyUi.setEnabled(page.understandRun, false)
                page.understandRun.text = "Stop"
            }
            else -> {
                BusyUi.setEnabled(page.understandRun, true)
                page.understandRun.text = "Stop"
                page.understandStatus.text = "● ${AppState.understandNote.ifBlank { "WORKING" }.uppercase(Locale.US)}"
                page.understandStatus.setTextColor(getColor(R.color.accent))
            }
        }
        paintBusy()
    }

    private fun runUnderstand() {
        if (AppState.understandBusy) {
            AppState.understandStop.set(true)
            AppState.understandNote = "Stopping after this step"
            UiBridge.understandUpdate()
            return
        }
        val root = vibeRoot()
        if (root == null) {
            pickUnderstandRepo()
            return
        }
        ensureProject(root)
        saveEditor(announce = false)
        saveProvider(currentProvider)
        val provider = currentProvider
        val key = store.get(provider, "key", "")
        val model = store.get(provider, "model", provider.defaultModel).ifBlank {
            binding.vibePage.model.text?.toString()?.trim().orEmpty().ifBlank { provider.defaultModel }
        }
        val base = store.get(provider, "base", provider.defaultBase).ifBlank {
            binding.vibePage.baseUrl.text?.toString()?.trim().orEmpty().ifBlank { provider.defaultBase }
        }
        if (key.isBlank() || model.isBlank()) {
            AppState.understandNote = "Add an API key in Settings"
            AppState.log("understand: add an API key in Settings")
            renderUnderstand()
            return
        }
        val focus = binding.understandPage.understandFocus.text?.toString()?.trim().orEmpty()
        AppState.understandRepo = root
        AppState.understandStop.set(false)
        AppState.understandNote = "Starting"
        AppState.understandText = ""
        understandRendered = ""
        understandShowSource = false
        UiBridge.understandUpdate()
        launchTask("Understanding ${root.name}", AppState.Tab.UNDERSTAND, setOf(Res.UNDERSTAND)) {
            var title = "Understand failed"
            var summary = ""
            try {
                DebugLog.step("understand", "start provider=${provider.id} model=$model")
                val result = Understand.run(
                    root, focus, provider, key, model, base, AppState.understandStop
                ) { line ->
                    AppState.understandNote = line
                    DebugLog.step("understand", line)
                    UiBridge.understandUpdate()
                }
                AppState.understandText = result.markdown
                AppState.understandNote = "${result.defs} defs · ${result.files} files"
                summary = "Documented ${root.name}: ${result.defs} defs in ${result.files} files"
                AppState.log(summary)
                DebugLog.step("understand", "done defs=${result.defs} files=${result.files}")
                title = if (AppState.understandStop.get()) "Understand stopped" else "Understand finished"
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                AppState.understandNote = msg
                if (AppState.understandText.isBlank()) {
                    AppState.understandText = "Understand failed: $msg"
                }
                summary = msg
                DebugLog.step("understand", "fail ${t.javaClass.simpleName}: $msg")
                AppState.log("understand error: $msg")
            } finally {
                AppState.understandStop.set(false)
                UiBridge.understandUpdate()
            }
            TaskRunner.Done(title, summary)
        }
    }

    private fun saveUnderstand() {
        val root = vibeRoot() ?: run {
            pickUnderstandRepo()
            return
        }
        val text = AppState.understandText
        if (text.isBlank()) {
            AppState.understandNote = "Nothing to save yet"
            renderUnderstand()
            return
        }
        try {
            val file = Understand.save(root, text)
            AppState.understandNote = "Saved ${file.name}"
            AppState.log("wrote ${RepoFiles.rel(file, root)}")
            if (!editing) refreshFileList()
            if (!AppState.gitBusy) {
                AppState.gitSnapshot = runCatching {
                    GitOps.snapshot(AppState.cwd, AppState.reposDir)
                }.getOrNull()
                UiBridge.gitUpdate()
            }
            UiBridge.filesChanged()
        } catch (t: Throwable) {
            AppState.understandNote = t.message ?: t.javaClass.simpleName
        }
        renderUnderstand()
    }

    private fun showVibeTab(index: Int) {
        val model = index == 2
        if (!model) saveProvider(currentProvider)
        binding.vibePage.chatPane.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.vibePage.historyPane.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.vibePage.modelPane.visibility = if (model) View.VISIBLE else View.GONE
        if (index == 1) renderHistory()
    }

    private fun renderHistory() {
        val page = binding.vibePage
        val list = page.historyList
        list.removeAllViews()
        val chats = ChatStore.chats()
        page.historyEmpty.visibility = if (chats.isEmpty()) View.VISIBLE else View.GONE
        BusyUi.setEnabled(page.newChat, !AppState.vibeBusy)
        val active = ChatStore.activeId()
        val stamp = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
        for (chat in chats) {
            val row = layoutInflater.inflate(R.layout.row_card, list, false)
            val title = row.findViewById<TextView>(R.id.cardTitle)
            title.text = chat.title
            title.maxLines = 2
            if (chat.id == active) title.setTextColor(getColor(R.color.accent))
            val messages = chat.turns.count { it.first != "steps" }
            row.findViewById<TextView>(R.id.cardBody).text =
                "${stamp.format(java.util.Date(chat.updated))} · $messages messages"
            row.setOnClickListener {
                if (AppState.vibeBusy) {
                    android.widget.Toast.makeText(
                        this, "Wait for the current reply to finish", android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                ChatStore.open(chat.id)
                renderChat()
                page.vibeTabs.getTabAt(0)?.select()
            }
            row.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle(chat.title)
                    .setMessage("Delete this chat.")
                    .setPositiveButton("Delete") { _, _ ->
                        ChatStore.delete(chat.id)
                        renderChat()
                        renderHistory()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            list.addView(row)
        }
    }

    private fun renderChat() {
        val list = binding.vibePage.chatList
        list.removeAllViews()
        val turns = AppState.chat.toList()
        val working = AppState.vibeBusy
        binding.vibePage.chatEmpty.visibility = if (turns.isEmpty() && !working) View.VISIBLE else View.GONE
        for ((role, text) in turns) list.addView(chatBubble(role, text))
        if (working) {
            val steps = synchronized(AppState.agentSteps) { AppState.agentSteps.takeLast(12) }
            if (steps.isNotEmpty()) list.addView(chatBubble("steps", steps.joinToString("\n")))
            list.addView(chatBubble("assistant", "Working…"))
        }
        binding.vibePage.chatScroll.post { binding.vibePage.chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun chatBubble(role: String, text: String): View {
        val user = role == "user"
        val steps = role == "steps"
        val bubble = TextView(this).apply {
            this.text = text
            if (steps) {
                setTextColor(getColor(R.color.muted))
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setPadding(dp(4), dp(4), dp(4), dp(4))
            } else {
                setTextColor(getColor(R.color.ink))
                textSize = 15f
                setBackgroundResource(R.drawable.bg_card)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
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
        page.gitPull.setOnClickListener {
            runGit("Pull") {
                AppState.gitDetail = null
                GitOps.pull(AppState.cwd, AppState.reposDir)
            }
        }
        page.gitPush.setOnClickListener {
            runGit("Push") {
                AppState.gitDetail = null
                GitOps.push(AppState.cwd, AppState.reposDir)
            }
        }
        page.gitBranch.setOnClickListener { showBranches() }
        page.gitMore.setOnClickListener { showGitMenu(it) }
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
        graph.scope.launch(graph.dispatchers.repo) {
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
        if (AppState.gitBusy) return
        val name = binding.gitPage.releaseName.text?.toString()?.trim().orEmpty()
        val notes = binding.gitPage.releaseNotes.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            AppState.log("Release needs a name")
            return
        }
        launchTask("Create release", AppState.Tab.GIT, setOf(Res.GIT)) {
            val text = try {
                GitOps.createTag(AppState.cwd, AppState.reposDir, name, notes)
            } catch (t: Throwable) {
                t.message ?: t.javaClass.simpleName
            }
            val tags = runCatching { GitOps.tags(AppState.cwd, AppState.reposDir) }.getOrDefault(emptyList())
            runOnUiThread {
                AppState.log(text)
                renderReleases(tags)
                if (text.startsWith("tagged ")) {
                    binding.gitPage.releaseName.setText("")
                    binding.gitPage.releaseNotes.setText("")
                }
                onGit()
            }
            TaskRunner.Done("Release finished", text)
        }
        onGit()
    }

    private fun setupBuild() {
        binding.buildPage.buildApk.setOnClickListener { startBuild() }
        binding.buildPage.reviseBuild.setOnClickListener { reviseBuild() }
        binding.buildPage.installApk.setOnClickListener { installBuiltApk() }
        binding.buildPage.savedHeader.setOnClickListener {
            savedApksOpen = !savedApksOpen
            renderSavedApks()
        }
        binding.buildPage.historyHeader.setOnClickListener {
            buildHistoryOpen = !buildHistoryOpen
            renderBuildHistory()
        }
    }

    private var buildHistoryOpen = false
    private var shownHistory = ""

    private fun renderBuildHistory() {
        val key = "${WorkspaceStore.current().id}:${BuildHistory.version}:$buildHistoryOpen"
        if (key == shownHistory) return
        shownHistory = key
        val page = binding.buildPage
        val entries = BuildHistory.list()
        page.historyCount.text = entries.size.toString()
        page.historyChevron.rotation = if (buildHistoryOpen) 90f else 0f
        page.historyScroll.visibility = if (buildHistoryOpen && entries.isNotEmpty()) View.VISIBLE else View.GONE
        if (!buildHistoryOpen) return
        val list = page.buildHistory
        list.removeAllViews()
        page.historyScroll.layoutParams = page.historyScroll.layoutParams.apply {
            height = if (entries.size > 3) dp(200) else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val stamp = SimpleDateFormat("MMM d, HH:mm", Locale.US)
        for (entry in entries) {
            val row = RowCardBinding.inflate(layoutInflater, list, false)
            row.cardTitle.text = (if (entry.ok) "● OK   " else "● FAIL ") + entry.repo.ifBlank { "—" }
            row.cardTitle.setTextColor(getColor(if (entry.ok) R.color.bid else R.color.ask))
            row.cardBody.text = buildString {
                append(stamp.format(Date(entry.started)))
                if (entry.apk.isNotBlank()) append(" · ").append(entry.apk)
                else if (entry.summary.isNotBlank()) append(" · ").append(entry.summary)
            }
            row.root.setOnClickListener { showBuildEntry(entry) }
            list.addView(row.root)
        }
    }

    private fun showBuildEntry(entry: BuildHistory.Entry) {
        val text = TextView(this).apply {
            this.text = BuildHistory.log(entry).ifBlank { "(empty log)" }
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(getColor(R.color.tape_ink))
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(text) }
        val stamp = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(entry.started))
        AlertDialog.Builder(this)
            .setTitle("${entry.repo.ifBlank { "Build" }} · $stamp")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Delete") { _, _ ->
                BuildHistory.delete(entry)
                renderBuildHistory()
            }
            .show()
    }

    private fun startBuild() {
        if (AppState.buildBusy || AppState.reviseBusy) return
        saveEditor(announce = false)
        val url = store.buildUrl()
        val token = store.buildToken()
        AppState.clearBuild()
        val appContext = applicationContext
        val started = System.currentTimeMillis()
        launchTask("Building APK", AppState.Tab.BUILD, setOf(Res.BUILD)) {
            var cloud = false
            var title = "Build failed"
            var summary = ""
            var built: File? = null
            var repo = ""
            try {
                val root = AppState.projectRoot()
                repo = root.name
                cloud = File(root, "gradlew").isFile
                DebugLog.step("build", "start path=${root.absolutePath} gradlew=$cloud")
                AppState.buildLog(RepoFiles.display(root, AppState.reposDir))
                if (cloud) {
                    if (url.isBlank() || token.isBlank()) {
                        DebugLog.step("build", "missing url or token")
                        title = "Build needs setup"
                        summary = "Set the build URL and token on Console → Variables."
                        AppState.buildLog(summary)
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
                        built = apk
                        title = "Build ready"
                        summary = "${root.name}: ${apk.name}"
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
                    built = apk
                    title = "Build ready"
                    summary = "${root.name}: ${apk.name}"
                }
            } catch (t: Throwable) {
                DebugLog.step("build", "fail ${t.javaClass.simpleName}: ${t.message}")
                val message = "build failed: ${t.message ?: t.javaClass.simpleName}"
                AppState.buildLog(message)
                if (cloud) AppState.log(message)
                summary = t.message ?: t.javaClass.simpleName
            } finally {
                BuildHistory.record(repo, started, built != null, built?.name, summary, AppState.buildText())
                UiBridge.buildUpdate()
            }
            TaskRunner.Done(title, summary, built)
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
        UiBridge.buildUpdate()
        val open = AppState.openFile
        val cwd = AppState.cwd
        launchTask("Revising from build log", AppState.Tab.BUILD, setOf(Res.REVISE)) {
            val note: (String) -> Unit = { line ->
                AppState.buildLog(line)
                AppState.log(line)
            }
            var title = "Revise failed"
            var summary = ""
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
                title = "Revise finished"
                summary = "${edit.written.size} files changed. Tap Build APK to compile again.\n\n${edit.report}"
            } catch (t: Throwable) {
                DebugLog.step("revise", "fail ${t.javaClass.simpleName}: ${t.message}")
                note("revise failed: ${t.message ?: t.javaClass.simpleName}")
                summary = t.message ?: t.javaClass.simpleName
            } finally {
                UiBridge.buildUpdate()
            }
            TaskRunner.Done(title, summary)
        }
    }

    private var shownApks = ""
    private var savedApksOpen = false

    private fun renderSavedApks() {
        val files = ApkLibrary.list(this)
        if (AppState.lastApk?.let { File(it).isFile } != true) {
            AppState.lastApk = files.firstOrNull()?.absolutePath
        }
        val page = binding.buildPage
        page.savedCount.text = files.size.toString()
        page.savedChevron.rotation = if (savedApksOpen) 90f else 0f
        page.savedScroll.visibility = if (savedApksOpen && files.isNotEmpty()) View.VISIBLE else View.GONE
        val key = files.joinToString { "${it.absolutePath}:${it.length()}" }
        val list = page.savedApks
        val expectedChildren = if (files.isEmpty()) 0 else files.size * 2 - 1
        if (key == shownApks && list.childCount == expectedChildren) return
        shownApks = key
        list.removeAllViews()
        val density = resources.displayMetrics.density
        page.savedScroll.layoutParams = page.savedScroll.layoutParams.apply {
            height = if (files.size > 3) (180 * density).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        for ((index, file) in files.withIndex()) {
            if (index > 0) {
                list.addView(View(this).apply {
                    setBackgroundColor(getColor(R.color.line))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, density.toInt().coerceAtLeast(1)))
            }
            val row = RowApkBinding.inflate(layoutInflater, list, false)
            row.apkName.text = file.name
            row.apkMeta.text = "${stamp.format(Date(file.lastModified()))} · ${file.length() / 1024} KB"
            row.apkInstall.setOnClickListener {
                AppState.lastApk = file.absolutePath
                installBuiltApk()
            }
            row.apkDelete.setOnClickListener { confirmDeleteApk(file) }
            list.addView(row.root)
        }
    }

    private fun confirmDeleteApk(file: File) {
        AlertDialog.Builder(this)
            .setMessage("Delete ${file.name}?")
            .setPositiveButton("Delete") { _, _ ->
                if (file.absolutePath == AppState.lastApk) AppState.lastApk = null
                file.delete()
                shownApks = ""
                onBuild()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            val tab = AppState.Tab.entries.firstOrNull { navId(it) == item.itemId } ?: return@setOnItemSelectedListener false
            showTab(tab)
            true
        }
        binding.bottomNav.setOnItemReselectedListener {
            if (settingsOpen) closeSettings()
            if (workspaceOpen) closeWorkspace()
        }
        binding.openConsole.setOnClickListener { showTab(AppState.Tab.CONSOLE) }
        showTab(AppState.tab)
    }

    private fun showTab(tab: AppState.Tab) {
        if (settingsOpen) closeSettings()
        if (workspaceOpen) closeWorkspace()
        if (tab != AppState.Tab.FILES) saveEditor(announce = false)
        applyTab(tab)
        syncNav(tab)
        when (tab) {
            AppState.Tab.BOARD -> renderBoard()
            AppState.Tab.BUILD -> onBuild()
            AppState.Tab.UNDERSTAND -> onUnderstand()
            AppState.Tab.SEARCH -> {
                renderFind()
                ensureFossFeed()
            }
            AppState.Tab.GIT -> if (AppState.gitDetail == null) refreshGit()
            AppState.Tab.FILES -> if (!editing) refreshFileList()
            else -> Unit
        }
    }

    private fun syncNav(tab: AppState.Tab) {
        val menu = binding.bottomNav.menu
        val id = navId(tab)
        if (id == null) {
            menu.setGroupCheckable(0, true, false)
            for (i in 0 until menu.size()) menu.getItem(i).isChecked = false
            menu.setGroupCheckable(0, true, true)
        } else {
            menu.findItem(id)?.isChecked = true
        }
        val console = tab == AppState.Tab.CONSOLE
        val color = getColor(if (console) R.color.accent else R.color.muted)
        binding.openConsole.setTextColor(color)
        binding.openConsole.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun navId(tab: AppState.Tab): Int? = when (tab) {
        AppState.Tab.BOARD -> R.id.nav_board
        AppState.Tab.FILES -> R.id.nav_files
        AppState.Tab.SEARCH -> R.id.nav_search
        AppState.Tab.GIT -> R.id.nav_git
        AppState.Tab.VIBE -> R.id.nav_vibe
        AppState.Tab.UNDERSTAND -> R.id.nav_understand
        AppState.Tab.BUILD -> R.id.nav_build
        AppState.Tab.CONSOLE -> null
    }

    private fun applyTab(tab: AppState.Tab) {
        AppState.tab = tab
        binding.consolePage.root.visibility = if (tab == AppState.Tab.CONSOLE) View.VISIBLE else View.GONE
        binding.boardPage.root.visibility = if (tab == AppState.Tab.BOARD) View.VISIBLE else View.GONE
        binding.filesPage.root.visibility = if (tab == AppState.Tab.FILES) View.VISIBLE else View.GONE
        binding.searchPage.root.visibility = if (tab == AppState.Tab.SEARCH) View.VISIBLE else View.GONE
        binding.gitPage.root.visibility = if (tab == AppState.Tab.GIT) View.VISIBLE else View.GONE
        binding.vibePage.root.visibility = if (tab == AppState.Tab.VIBE) View.VISIBLE else View.GONE
        binding.understandPage.root.visibility = if (tab == AppState.Tab.UNDERSTAND) View.VISIBLE else View.GONE
        binding.buildPage.root.visibility = if (tab == AppState.Tab.BUILD) View.VISIBLE else View.GONE
    }

    private fun refreshFileList() {
        val cwd = AppState.cwd
        val repos = AppState.reposDir
        binding.filesPage.filesPath.text = AppState.downloadNote ?: RepoFiles.display(cwd, repos)
        binding.filesPage.filesUp.isEnabled = cwd.canonicalPath != repos.canonicalPath
        val atRoot = cwd.canonicalPath == repos.canonicalPath
        val files = cwd.listFiles()
            ?.filter { it.name != ".git" && (!atRoot || WorkspaceStore.contains(it)) }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .orEmpty()
        displayed = files
        val iconPx = (16 * resources.displayMetrics.density).toInt()
        binding.filesPage.fileList.adapter = object : ArrayAdapter<File>(this, R.layout.row_file_entry, files) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val row = (convertView ?: layoutInflater.inflate(R.layout.row_file_entry, parent, false))
                    as android.widget.TextView
                val file = files[position]
                row.text = file.name
                row.setCompoundDrawablesRelative(FileIcons.forFile(file, iconPx), null, null, null)
                return row
            }
        }
        binding.filesPage.filesEmpty.text = AppState.downloadNote ?: if (atRoot) {
            if (WorkspaceStore.downloaded().isEmpty()) {
                "Find a repo on Search, or tap Open for a folder already on this phone."
            } else {
                "No repos in ${WorkspaceStore.current().name}. Tap Projects to add downloaded repos, or find one on Search."
            }
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
            paintFilesBusy()
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
        if (AppState.vibeBusy) {
            AppState.agentStop.set(true)
            UiBridge.vibeUpdate()
            return
        }
        val instruction = binding.vibePage.vibePrompt.text?.toString()?.trim().orEmpty()
        if (instruction.isEmpty()) return
        binding.vibePage.vibePrompt.setText("")
        startVibe(instruction)
    }

    private fun startVibe(instruction: String) {
        if (instruction.isBlank() || AppState.vibeBusy) return
        val root = vibeRoot()
        if (root != null) ensureProject(root)
        saveEditor(announce = false)
        saveProvider(currentProvider)
        AppState.chat.add("user" to instruction)
        ChatStore.save()
        val provider = currentProvider
        val key = store.get(provider, "key", "")
        val model = binding.vibePage.model.text?.toString()?.trim().orEmpty()
        val base = binding.vibePage.baseUrl.text?.toString()?.trim().orEmpty()
        val open = AppState.openFile
        val cwd = AppState.cwd
        AppState.vibeRepo = root
        AppState.vibeResult = ""
        AppState.writtenPaths = emptyList()
        AppState.agentStop.set(false)
        synchronized(AppState.agentSteps) { AppState.agentSteps.clear() }
        showTab(AppState.Tab.VIBE)
        binding.vibePage.vibeTabs.getTabAt(0)?.select()
        UiBridge.vibeUpdate()
        runAgent(instruction, provider, key, model, base, open, cwd, root)
    }

    private fun runAgent(
        instruction: String,
        provider: Provider,
        key: String,
        model: String,
        base: String,
        open: File?,
        cwd: File,
        start: File?
    ) {
        val appContext = applicationContext
        val buildUrl = store.buildUrl()
        val buildToken = store.buildToken()
        val earlier = AppState.history.toList()
        launchTask("Agent working", AppState.Tab.VIBE, setOf(Res.AGENT), lane = graph.dispatchers.agent) {
            DebugLog.step("agent", "start provider=${provider.id} model=$model chars=${instruction.length}")
            var started: AgentContext? = null
            var title = "Agent error"
            try {
                val ctx = AgentContext(start, AppState.reposDir, appContext, buildUrl, buildToken)
                started = ctx
                val job = AgentJob(
                    ctx, cwd, open, instruction, provider, key, model, base, earlier, AppState.agentStop
                )
                val result = Agent.run(job) { line ->
                    synchronized(AppState.agentSteps) {
                        AppState.agentSteps.add(line)
                        if (AppState.agentSteps.size > 300) AppState.agentSteps.removeAt(0)
                    }
                    DebugLog.step("agent", line)
                    UiBridge.vibeUpdate()
                }
                val root = ctx.root
                if (root != null && root.canonicalFile != start?.canonicalFile) {
                    AppState.cwd = root
                    ProjectStore.remember(appContext, root)
                    UiBridge.projectChanged()
                }
                val report = buildString {
                    append(result.text)
                    if (root != null && result.changed.isNotEmpty()) {
                        append("\n\nChanged in ").append(root.name).append(':')
                        result.changed.forEach { file ->
                            append("\n").append(RepoFiles.rel(file, root))
                            if (!file.exists()) append(" (deleted)")
                        }
                    }
                }
                AppState.history.addLast("user" to instruction.take(2000))
                AppState.history.addLast("assistant" to report.take(2000))
                while (AppState.history.size > 8) AppState.history.removeFirst()
                if (result.changed.isNotEmpty() && !AppState.gitBusy) {
                    AppState.gitSnapshot = runCatching {
                        GitOps.snapshot(AppState.cwd, AppState.reposDir)
                    }.getOrNull()
                    UiBridge.gitUpdate()
                }
                AppState.writtenPaths = result.changed.filter { it.isFile }.map { it.canonicalPath }
                AppState.vibeResult = report
                DebugLog.step("agent", "done steps=${result.steps} files=${result.changed.size}")
                AppState.log(report)
                title = if (AppState.agentStop.get()) "Agent stopped" else "Agent finished"
            } catch (t: Throwable) {
                val ctx = started
                val changed = ctx?.changed?.toList().orEmpty()
                val msg = buildString {
                    append(t.message ?: t.javaClass.simpleName)
                    val root = ctx?.root
                    if (root != null && changed.isNotEmpty()) {
                        append("\n\nChanged in ").append(root.name).append(" before the error:")
                        changed.forEach { append("\n").append(RepoFiles.rel(it, root)) }
                    }
                }
                AppState.vibeResult = msg
                AppState.writtenPaths = changed.filter { it.isFile }.map { it.canonicalPath }
                if (changed.isNotEmpty()) UiBridge.gitUpdate()
                DebugLog.step("agent", "fail ${t.javaClass.simpleName}: $msg")
                AppState.log("agent error: $msg")
            } finally {
                AppState.agentStop.set(false)
                UiBridge.vibeUpdate()
            }
            TaskRunner.Done(title, AppState.vibeResult)
        }
    }

    private fun syncBack() {
        backCallback.isEnabled = settingsOpen || workspaceOpen || editing || AppState.gitDetail != null
    }

    private fun pickFolder() {
        if (AppState.importBusy || AppState.downloadBusy) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        openFolder.launch(intent)
    }

    private fun startImport(uri: Uri) {
        if (AppState.importBusy || AppState.downloadBusy) return
        saveEditor(announce = false)
        val app = applicationContext
        AppState.downloadNote = "Importing folder…"
        AppState.log("importing folder…")
        refreshFileList()
        launchTask("Importing folder", AppState.Tab.FILES, setOf(Res.IMPORT)) {
            try {
                val dest = FolderImport.importTree(app, uri, AppState.reposDir, AppState::log)
                WorkspaceStore.include(dest.name)
                AppState.cwd = dest.canonicalFile
                ProjectStore.remember(app, AppState.cwd)
                AppState.gitDetail = null
                AppState.gitSnapshot = null
                UiBridge.projectChanged()
                TaskRunner.Done("Import finished", "${dest.name} is open in Files.")
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                AppState.log("open failed: $msg")
                TaskRunner.Done("Import failed", msg)
            } finally {
                AppState.downloadNote = null
                UiBridge.filesChanged()
            }
        }
        paintBusy()
    }

    private fun showProjects() {
        val dirs = WorkspaceStore.activeRepos()
        if (dirs.isEmpty()) {
            if (WorkspaceStore.downloaded().isEmpty()) {
                AppState.log("No saved projects. Clone one, or tap Open.")
            } else {
                openWorkspace()
            }
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Open project")
            .setItems(dirs.map { it.name }.toTypedArray()) { _, which -> openProject(dirs[which]) }
            .setNeutralButton("Edit repos") { _, _ -> openWorkspace() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openProject(dir: File) {
        if (editing) closeEditor(save = true)
        WorkspaceStore.include(dir.name)
        AppState.cwd = dir.canonicalFile
        ProjectStore.remember(this, AppState.cwd)
        AppState.gitDetail = null
        AppState.gitSnapshot = null
        refreshFileList()
        if (AppState.tab == AppState.Tab.GIT) refreshGit()
        AppState.log("opened ${dir.name}")
        renderVibeRepo()
        paintTape()
        paintProject()
        if (workspaceOpen) renderRepoChecks()
    }

    private fun refreshGit() {
        if (AppState.gitBusy) return
        launchTask("Git working", AppState.Tab.GIT, setOf(Res.GIT), track = false) {
            AppState.gitSnapshot = runCatching {
                GitOps.snapshot(AppState.cwd, AppState.reposDir)
            }.getOrElse {
                GitOps.Snapshot("", it.message ?: "git failed", emptyList(), false)
            }
            UiBridge.gitUpdate()
            null
        }
        onGit()
    }

    private fun closeGitDetail() {
        AppState.gitDetail = null
        onGit()
    }

    private fun runGit(label: String? = null, block: () -> String?) {
        if (AppState.gitBusy) return
        saveEditor(announce = false)
        launchTask(label ?: "Git working", AppState.Tab.GIT, setOf(Res.GIT), track = label != null) {
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
            UiBridge.gitUpdate()
            UiBridge.filesChanged()
            if (label != null) TaskRunner.Done("$label finished", msg.orEmpty()) else null
        }
        onGit()
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
        page.gitName.setText(store.gitName().ifBlank { "AndVibe" })
        page.gitEmail.setText(store.gitEmail().ifBlank { "andvibe@local" })
        page.gitHttpsUser.setText(store.gitUser())
        page.gitHttpsToken.setText(store.gitToken())
        page.gitSsh.setText(store.gitSsh())
        page.gitOrigin.setText(
            runCatching { GitOps.originUrl(AppState.cwd, AppState.reposDir) }.getOrDefault("")
        )
        page.gitSettingsNote.text = ""
    }

    private fun saveGitSettings() {
        val page = binding.settingsPage
        val name = page.gitName.text?.toString()?.trim().orEmpty().ifBlank { "AndVibe" }
        val email = page.gitEmail.text?.toString()?.trim().orEmpty().ifBlank { "andvibe@local" }
        val user = page.gitHttpsUser.text?.toString()?.trim().orEmpty()
        val token = page.gitHttpsToken.text?.toString().orEmpty()
        val ssh = page.gitSsh.text?.toString().orEmpty()
        store.saveGit(name, email, user, token, ssh)
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
        val staged = snap.changes.filter { it.staged }
        val unstaged = snap.changes.filter { it.unstaged }
        if (staged.isNotEmpty()) {
            val open = gitSection(parent, "Staged Changes", staged.size, R.drawable.ic_git_minus, "Unstage all") {
                GitOps.unstageAll(AppState.cwd, AppState.reposDir)
            }
            if (open) staged.forEach { gitFileRow(parent, it, stage = false) }
        }
        if (unstaged.isNotEmpty()) {
            val open = gitSection(parent, "Changes", unstaged.size, R.drawable.ic_git_plus, "Stage all") {
                GitOps.stageAll(AppState.cwd, AppState.reposDir)
            }
            if (open) unstaged.forEach { gitFileRow(parent, it, stage = true) }
        }
        if (snap.changes.isEmpty()) gitNote(parent, "No changes")
        if (gitSection(parent, "Commits", snap.commits.size, 0, "", null)) {
            if (snap.commits.isEmpty()) gitNote(parent, "No commits yet")
            else snap.commits.forEach { gitCommitRow(parent, it) }
        }
    }

    private fun gitSection(
        parent: LinearLayout,
        title: String,
        count: Int,
        actionIcon: Int,
        actionLabel: String,
        action: (() -> String?)?
    ): Boolean {
        val row = RowGitSectionBinding.inflate(layoutInflater, parent, false)
        val open = title !in gitCollapsed
        row.sectionTitle.text = title.uppercase()
        row.sectionChevron.rotation = if (open) 90f else 0f
        row.sectionCount.text = count.toString()
        row.root.setOnClickListener {
            if (!gitCollapsed.remove(title)) gitCollapsed.add(title)
            onGit()
        }
        if (action != null) {
            row.sectionAction.visibility = View.VISIBLE
            row.sectionAction.setImageResource(actionIcon)
            row.sectionAction.contentDescription = actionLabel
            BusyUi.setEnabled(row.sectionAction, !AppState.gitBusy)
            row.sectionAction.setOnClickListener {
                if (AppState.gitBusy) return@setOnClickListener
                runGit {
                    AppState.gitDetail = null
                    action()
                }
            }
        }
        parent.addView(row.root)
        return open
    }

    private fun gitNote(parent: LinearLayout, text: String) {
        val view = TextView(this)
        view.text = text
        view.setTextColor(ContextCompat.getColor(this, R.color.muted))
        view.textSize = 12f
        view.setPadding(dp(22), dp(6), 0, dp(6))
        parent.addView(view)
    }

    private fun gitFileRow(parent: LinearLayout, change: GitOps.Change, stage: Boolean) {
        val row = RowGitFileBinding.inflate(layoutInflater, parent, false)
        val code = if (stage) change.code.getOrElse(1) { ' ' } else change.code.getOrElse(0) { ' ' }
        val (letter, color) = when (code) {
            'M' -> "M" to R.color.git_modified
            'A' -> "A" to R.color.git_added
            '?' -> "U" to R.color.git_added
            'D' -> "D" to R.color.git_deleted
            'U' -> "!" to R.color.git_conflict
            else -> code.toString().trim() to R.color.muted
        }
        val tint = getColor(color)
        val name = change.path.substringAfterLast('/')
        val dir = change.path.substringBeforeLast('/', "")
        val label = android.text.SpannableStringBuilder(name)
        if (code == 'D') {
            label.setSpan(android.text.style.StrikethroughSpan(), 0, name.length, 0)
        }
        if (dir.isNotEmpty()) {
            val start = label.length
            label.append("  ").append(dir)
            label.setSpan(
                android.text.style.ForegroundColorSpan(getColor(R.color.muted)), start, label.length, 0
            )
            label.setSpan(android.text.style.RelativeSizeSpan(0.85f), start, label.length, 0)
        }
        row.gitPath.text = label
        row.gitPath.setTextColor(tint)
        row.gitStatus.text = letter
        row.gitStatus.setTextColor(tint)
        row.gitIcon.setImageDrawable(FileIcons.forFile(File(change.path), dp(16)))
        row.gitMark.setImageResource(if (stage) R.drawable.ic_git_plus else R.drawable.ic_git_minus)
        row.gitMark.contentDescription = if (stage) "Stage" else "Unstage"
        row.gitDiscard.visibility = if (stage) View.VISIBLE else View.GONE
        val enabled = !AppState.gitBusy
        BusyUi.setEnabled(row.root, enabled)
        BusyUi.setEnabled(row.gitMark, enabled)
        BusyUi.setEnabled(row.gitDiscard, enabled)
        row.gitDiscard.setOnClickListener { if (enabled) confirmDiscard(change) }
        row.root.setOnClickListener { if (enabled) showChangeMenu(change) }
        row.gitMark.setOnClickListener {
            if (!enabled) return@setOnClickListener
            runGit {
                AppState.gitDetail = null
                if (stage) GitOps.stage(AppState.cwd, AppState.reposDir, change.path)
                else GitOps.unstage(AppState.cwd, AppState.reposDir, change.path)
            }
        }
        parent.addView(row.root)
    }

    private fun gitCommitRow(parent: LinearLayout, commit: GitOps.CommitLine) {
        val row = RowGitCommitBinding.inflate(layoutInflater, parent, false)
        row.commitSubject.text = commit.subject
        row.commitMeta.text = "${commit.id} · ${commit.whenText}"
        row.root.setOnClickListener {
            AppState.gitDetail = "${commit.id} ${commit.whenText}\n${commit.subject}"
            onGit()
        }
        parent.addView(row.root)
    }

    private fun showGitMenu(anchor: View) {
        val menu = android.widget.PopupMenu(this, anchor)
        val actions = listOf<Pair<String, () -> Unit>>(
            "Stage all" to { runGit { AppState.gitDetail = null; GitOps.stageAll(AppState.cwd, AppState.reposDir) } },
            "Unstage all" to { runGit { AppState.gitDetail = null; GitOps.unstageAll(AppState.cwd, AppState.reposDir) } },
            "Fetch" to { runGit("Fetch") { AppState.gitDetail = null; GitOps.fetch(AppState.cwd, AppState.reposDir) } },
            "Checkout branch…" to { showBranches() },
            "Full log" to {
                runGit {
                    AppState.gitDetail = GitOps.history(AppState.cwd, AppState.reposDir)
                    null
                }
            },
            "Initialize repository" to {
                runGit {
                    AppState.gitDetail = null
                    val dir = runCatching { AppState.projectRoot() }.getOrElse {
                        return@runGit it.message ?: "Open a project first."
                    }
                    GitOps.init(dir)
                }
            }
        )
        actions.forEachIndexed { i, (label, _) -> menu.menu.add(0, i, i, label) }
        menu.setOnMenuItemClickListener { item ->
            actions.getOrNull(item.itemId)?.second?.invoke()
            true
        }
        menu.show()
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
        val cwd = AppState.cwd
        val repos = AppState.reposDir
        launchTask("Git working", AppState.Tab.GIT, setOf(Res.GIT), track = false) {
            val text = try {
                val files = snap.changes.joinToString("\n") { it.label }
                val diff = GitOps.diff(cwd, repos, null, false).take(4000)
                val staged = GitOps.diff(cwd, repos, null, true).take(2000)
                val raw = AiClient.complete(
                    PromptStore.get(PromptStore.Kind.COMMIT),
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
            runOnUiThread {
                if (!isFinishing && text.isNotEmpty()) binding.gitPage.gitMessage.setText(text)
                onGit()
            }
            null
        }
        onGit()
    }

    private fun showBranches() {
        if (AppState.gitBusy) return
        graph.scope.launch(graph.dispatchers.repo) {
            val names = try {
                GitOps.branches(AppState.cwd, AppState.reposDir)
            } catch (t: Throwable) {
                AppState.log(t.message ?: "git failed")
                null
            } ?: return@launch
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val items = (names + "New branch").toTypedArray()
                AlertDialog.Builder(this@MainActivity)
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
