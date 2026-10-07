package com.example.andvibe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.PixelCopy
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.andvibe.core.GitClient
import com.example.andvibe.databinding.ActivityMainBinding
import com.example.andvibe.shell.ScreenshotSource
import com.example.andvibe.shell.ShellEffect
import com.example.andvibe.tasks.Res
import com.example.andvibe.ui.MainControllers
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Navigation shell: inflate, wire controllers, bottom nav, permissions, and shell effects.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SecretStore
    private lateinit var controllers: MainControllers
    private var currentTab: Tab = Tab.CONSOLE
    private var warnedPlain = false
    private val graph get() = (application as AndVibeApp).graph
    private val editing: Boolean
        get() = ::controllers.isInitialized && controllers.files.isEditing

    private val screenshotSource = object : ScreenshotSource {
        override suspend fun capture(tab: Tab?): Bitmap? = suspendCancellableCoroutine { cont ->
            if (!::binding.isInitialized || isFinishing) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            if (tab != null && (tab != currentTab ||
                    (::controllers.isInitialized && controllers.settings.isOpen) ||
                    (::controllers.isInitialized && controllers.workspace.isOpen))
            ) {
                showTab(tab)
            }
            val root = window.decorView
            root.postDelayed({
                captureWindow(root) { bitmap ->
                    if (cont.isActive) cont.resume(bitmap)
                }
            }, if (tab == null) 50L else 400L)
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
        val existing = File(graph.session.reposDir, safe)
        if (existing.isDirectory && !existing.list().isNullOrEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(safe)
                .setMessage("This name is already in AndVibe. Open the saved project, or import another copy.")
                .setPositiveButton("Open it") { _, _ -> controllers.files.openProject(existing) }
                .setNeutralButton("Import copy") { _, _ -> controllers.files.startImport(uri) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            controllers.files.startImport(uri)
        }
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        askBatteryExemption()
        if (::controllers.isInitialized) controllers.settings.renderBackground()
    }

    private var micPermissionCallback: ((Boolean) -> Unit)? = null
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val cb = micPermissionCallback
        micPermissionCallback = null
        cb?.invoke(granted)
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (::controllers.isInitialized && controllers.settings.isOpen) {
                controllers.settings.close()
                return
            }
            if (::controllers.isInitialized && controllers.workspace.isOpen) {
                controllers.workspace.close()
                return
            }
            if (currentTab == Tab.GIT && ::controllers.isInitialized && controllers.git.hasDetail()) {
                controllers.git.closeDetail()
                return
            }
            if (editing) controllers.files.closeEditor(save = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = graph.secrets
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        onBackPressedDispatcher.addCallback(this, backCallback)
        if (store.plain && !warnedPlain) {
            warnedPlain = true
            logLine("API keys could not be encrypted on this device. They stay in app-private storage.")
        }
        controllers = MainControllers(
            activity = this,
            binding = binding,
            graph = graph,
            store = store,
            openFolder = openFolder,
            currentTab = { currentTab },
            showTab = { showTab(it) },
            editing = { editing },
            paintBusy = { paintBusy() },
            paintTape = { paintTape() },
            paintProject = { paintProject() },
            onUsage = { onUsage() },
            syncBack = { syncBack() },
            logLine = { logLine(it) },
            ensureTrackedBackground = { ensureTrackedBackground() },
            requestBackground = { requestBackground() },
            batteryExempt = { batteryExempt() },
            requestMicPermission = { onResult ->
                micPermissionCallback = onResult
                askMic.launch(Manifest.permission.RECORD_AUDIO)
            },
        )
        controllers.startAll()
        setupNav()
        collectShellEffects()
        onUsage()
        val open = graph.session.openFile
        if (open != null && open.isFile) controllers.files.openEditor(open)
        else controllers.files.refreshFileList()
        controllers.console.onLog()
        controllers.build.onExternalUpdate()
        controllers.git.onExternalUpdate()
        openTabFrom(intent)
        paintBusy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openTabFrom(intent)
    }

    override fun onStart() {
        super.onStart()
        graph.shell.screenshotSource = screenshotSource
        graph.tasks.visible = true
        graph.tasks.resync()
        if (::controllers.isInitialized && controllers.settings.isOpen) {
            controllers.settings.renderBackground()
        }
    }

    override fun onStop() {
        if (graph.shell.screenshotSource === screenshotSource) {
            graph.shell.screenshotSource = null
        }
        graph.tasks.visible = false
        super.onStop()
    }

    override fun onPause() {
        if (::controllers.isInitialized) {
            controllers.board.stopSpeech()
            controllers.files.saveEditor(announce = false)
            if (controllers.settings.isOpen) controllers.settings.saveApiKeys(announce = false)
            controllers.vibe.saveCurrentProvider()
            controllers.workspace.saveWorkspaceName()
        }
        super.onPause()
    }

    private fun collectShellEffects() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                graph.shell.effectsFlow.collect { effect ->
                    when (effect) {
                        ShellEffect.LogChanged -> controllers.console.onLog()
                        ShellEffect.FilesChanged -> controllers.files.onFilesChanged()
                        ShellEffect.ProjectChanged -> onProject()
                        ShellEffect.BuildChanged -> controllers.build.onExternalUpdate()
                        ShellEffect.BusyChanged -> onBusy()
                        ShellEffect.McpChanged -> controllers.settings.renderMcp()
                        ShellEffect.UsageChanged -> onUsage()
                        is ShellEffect.OpenFile -> {
                            controllers.files.openEditor(effect.file)
                            binding.bottomNav.selectedItemId = R.id.nav_files
                        }
                        is ShellEffect.PreviewFile -> startActivity(
                            Intent(this@MainActivity, PreviewActivity::class.java)
                                .putExtra(PreviewActivity.EXTRA_PATH, effect.file.absolutePath),
                        )
                    }
                }
            }
        }
    }

    private fun onBusy() {
        if (!::binding.isInitialized) return
        graph.understandFeature.syncBusy()
        graph.vibeFeature.syncBusy()
        graph.gitFeature.syncBusy()
        paintBusy()
    }

    private fun paintBusy() {
        val label = graph.busyLabel()
        val busy = label != null
        binding.busyBar.visibility = if (busy) View.VISIBLE else View.GONE
        binding.busyTrack.visibility = if (busy) View.VISIBLE else View.GONE
        binding.busyLabel.text = label.orEmpty()
        if (::controllers.isInitialized) {
            controllers.console.paintBusy()
            controllers.files.paintBusy()
        }
    }

    private fun paintTape() {
        if (::controllers.isInitialized) controllers.console.paintTape()
    }

    private fun onUsage() {
        if (!::binding.isInitialized) return
        val ws = WorkspaceStore.current()
        binding.openWorkspace.text = ws.name
        paintProject()
        binding.usageTokens.text = (ws.inputTokens + ws.outputTokens).toString()
        binding.usagePrice.text = WorkspaceStore.priceText(ws.costMicros)
        if (!::controllers.isInitialized) return
        if (currentTab == Tab.BOARD) controllers.board.renderBoard()
        controllers.vibe.onUsageSync()
        if (binding.vibePage.historyPane.visibility == View.VISIBLE) controllers.vibe.renderHistory()
    }

    private fun paintProject() {
        if (!::binding.isInitialized) return
        val name = graph.session.selectedRoot()?.name
        binding.openProject.text = name ?: "—"
        binding.openProject.setTextColor(getColor(if (name == null) R.color.muted else R.color.accent))
    }

    private fun onProject() {
        if (!::controllers.isInitialized) return
        controllers.files.onProjectChanged()
        if (currentTab == Tab.GIT) graph.gitFeature.refresh() else controllers.git.onExternalUpdate()
        if (currentTab == Tab.BUILD) controllers.build.onExternalUpdate()
        controllers.understand.onProjectChanged(graph.tasks.holds(Res.UNDERSTAND))
        graph.vibeFeature.markProjectChanged()
        paintTape()
        paintProject()
        if (controllers.workspace.isOpen) controllers.workspace.renderRepoChecks()
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

    private fun logLine(line: String) {
        graph.consoleLog.append(line)
        graph.shell.logChanged()
        DebugLog.step("console", line)
    }

    private fun ensureTrackedBackground() {
        val prefs = getSharedPreferences("andvibe_background", MODE_PRIVATE)
        if (!prefs.getBoolean("asked", false)) {
            prefs.edit().putBoolean("asked", true).apply()
            requestBackground()
        }
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
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        } catch (_: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun openTabFrom(intent: Intent?) {
        val name = intent?.getStringExtra(Notify.EXTRA_TAB) ?: return
        intent.removeExtra(Notify.EXTRA_TAB)
        val tab = Tab.entries.firstOrNull { it.name == name } ?: return
        showTab(tab)
    }

    private fun setupNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            val tab = Tab.entries.firstOrNull { navId(it) == item.itemId } ?: return@setOnItemSelectedListener false
            showTab(tab)
            true
        }
        binding.bottomNav.setOnItemReselectedListener {
            if (::controllers.isInitialized) {
                controllers.settings.close()
                controllers.workspace.close()
            }
        }
        binding.openConsole.setOnClickListener { showTab(Tab.CONSOLE) }
        showTab(currentTab)
    }

    private fun showTab(tab: Tab) {
        if (::controllers.isInitialized) {
            controllers.settings.close()
            controllers.workspace.close()
            if (tab != Tab.FILES) controllers.files.saveEditor(announce = false)
            if (tab != Tab.BOARD) controllers.board.stopSpeech()
        }
        applyTab(tab)
        syncNav(tab)
        if (!::controllers.isInitialized) return
        when (tab) {
            Tab.BOARD -> {
                graph.boardFeature.refresh()
                controllers.board.renderBoard()
            }
            Tab.BUILD -> {
                controllers.build.onTabVisible()
                controllers.build.onExternalUpdate()
            }
            Tab.UNDERSTAND -> controllers.understand.onTabVisible()
            Tab.SEARCH -> controllers.search.onTabVisible()
            Tab.GIT -> controllers.git.onTabVisible()
            Tab.VIBE -> controllers.vibe.onTabVisible()
            Tab.FILES -> controllers.files.onTabVisible()
            else -> Unit
        }
    }

    private fun syncNav(tab: Tab) {
        val menu = binding.bottomNav.menu
        val id = navId(tab)
        if (id == null) {
            menu.setGroupCheckable(0, true, false)
            for (i in 0 until menu.size()) menu.getItem(i).isChecked = false
            menu.setGroupCheckable(0, true, true)
        } else {
            menu.findItem(id)?.isChecked = true
        }
        val console = tab == Tab.CONSOLE
        val tint = getColor(if (console) R.color.accent else R.color.muted)
        binding.openConsole.setTextColor(tint)
        binding.openConsole.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(tint)
    }

    private fun navId(tab: Tab): Int? = when (tab) {
        Tab.BOARD -> R.id.nav_board
        Tab.FILES -> R.id.nav_files
        Tab.SEARCH -> R.id.nav_search
        Tab.GIT -> R.id.nav_git
        Tab.VIBE -> R.id.nav_vibe
        Tab.UNDERSTAND -> R.id.nav_understand
        Tab.BUILD -> R.id.nav_build
        Tab.CONSOLE -> null
    }

    private fun applyTab(tab: Tab) {
        currentTab = tab
        binding.consolePage.root.visibility = if (tab == Tab.CONSOLE) View.VISIBLE else View.GONE
        binding.boardPage.root.visibility = if (tab == Tab.BOARD) View.VISIBLE else View.GONE
        binding.filesPage.root.visibility = if (tab == Tab.FILES) View.VISIBLE else View.GONE
        binding.searchPage.root.visibility = if (tab == Tab.SEARCH) View.VISIBLE else View.GONE
        binding.gitPage.root.visibility = if (tab == Tab.GIT) View.VISIBLE else View.GONE
        binding.vibePage.root.visibility = if (tab == Tab.VIBE) View.VISIBLE else View.GONE
        binding.understandPage.root.visibility = if (tab == Tab.UNDERSTAND) View.VISIBLE else View.GONE
        binding.buildPage.root.visibility = if (tab == Tab.BUILD) View.VISIBLE else View.GONE
    }

    private fun syncBack() {
        if (!::controllers.isInitialized) {
            backCallback.isEnabled = false
            return
        }
        backCallback.isEnabled =
            controllers.settings.isOpen ||
                controllers.workspace.isOpen ||
                editing ||
                controllers.git.hasDetail()
    }
}
