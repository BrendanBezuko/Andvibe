package com.example.andvibe.ui

import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.andvibe.ApkInstaller
import com.example.andvibe.ApkLibrary
import com.example.andvibe.BuildHistory
import com.example.andvibe.BusyUi
import com.example.andvibe.Provider
import com.example.andvibe.R
import com.example.andvibe.WorkspaceStore
import com.example.andvibe.core.JsRunner
import com.example.andvibe.core.RepoFiles
import com.example.andvibe.databinding.PageBuildBinding
import com.example.andvibe.databinding.RowApkBinding
import com.example.andvibe.databinding.RowCardBinding
import com.example.andvibe.features.build.BuildFeature
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Build tab: log, APK install, saved APKs, and build history.
 */
class BuildPageController(
    private val activity: AppCompatActivity,
    private val page: PageBuildBinding,
    private val feature: BuildFeature,
    private val buildUrl: () -> String,
    private val buildToken: () -> String,
    private val providerCreds: (Provider) -> BuildFeature.Creds,
    private val currentProvider: () -> Provider,
    private val saveProvider: () -> Unit,
    private val selectedRoot: () -> File?,
    private val reposDir: () -> File,
    private val onBeforeBuildAction: () -> Unit,
    private val paintBusy: () -> Unit,
    private val color: (Int) -> Int,
) {
    private var lastHistoryKey = ""
    private var buildHistoryOpen = false
    private var shownHistory = ""
    private var shownApks = ""
    private var savedApksOpen = false

    fun start() {
        page.buildApk.setOnClickListener {
            onBeforeBuildAction()
            feature.build(buildUrl(), buildToken())
        }
        page.reviseBuild.setOnClickListener {
            onBeforeBuildAction()
            saveProvider()
            feature.revise(providerCreds(currentProvider()))
        }
        page.installBuildTools.setOnClickListener {
            onBeforeBuildAction()
            feature.installBuildTools()
        }
        page.installApk.setOnClickListener {
            val path = feature.state.value.lastApkPath
            installBuiltApk(path?.let { File(it) })
        }
        page.savedHeader.setOnClickListener {
            savedApksOpen = !savedApksOpen
            renderSavedApks()
        }
        page.historyHeader.setOnClickListener {
            buildHistoryOpen = !buildHistoryOpen
            renderBuildHistory()
        }

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { feature.state.collect { render(it) } }
                launch {
                    feature.buildLog.revision.collect {
                        maybeRefreshHistory()
                    }
                }
            }
        }
    }

    fun onTabVisible() {
        feature.syncBusy()
        page.buildScope.text = "WORKSPACE · ${WorkspaceStore.current().name.uppercase(Locale.US)}"
        maybeRefreshHistory(force = true)
    }

    fun onExternalUpdate() {
        maybeRefreshHistory(force = true)
        renderSavedApks()
        renderBuildHistory()
    }

    fun clearShownApks() {
        shownApks = ""
    }

    private fun maybeRefreshHistory(force: Boolean = false) {
        val key = "${WorkspaceStore.current().id}:${BuildHistory.version}"
        if (!force && key == lastHistoryKey) return
        lastHistoryKey = key
        renderSavedApks()
        renderBuildHistory()
    }

    private fun render(state: BuildFeature.State) {
        val scroll = page.buildScroll
        val child = scroll.getChildAt(0)
        val nearBottom = child == null || child.bottom <= scroll.height + scroll.scrollY + 160
        page.buildLog.text = state.logText

        val root = selectedRoot()
        page.projectLine.text = if (root == null) {
            "No repo yet. Clone one from Search."
        } else {
            RepoFiles.display(root, reposDir())
        }
        page.projectKind.text = root?.let { JsRunner.detect(it) }.orEmpty()
        page.projectKind.visibility = if (root == null) View.GONE else View.VISIBLE
        page.apkPath.text = state.lastApkPath?.let { File(it).name } ?: "No APK yet"

        val busy = state.building || state.revising
        val apkReady = state.lastApkPath?.let { File(it).isFile } == true
        val (status, statusColor) = when {
            state.building -> "● BUILDING" to R.color.quote
            state.revising -> "● REVISING" to R.color.quote
            apkReady -> "● APK READY" to R.color.bid
            else -> "● NOT BUILT" to R.color.muted
        }
        page.buildStatus.text = status
        page.buildStatus.setTextColor(color(statusColor))
        page.installApk.isEnabled = apkReady
        BusyUi.setEnabled(page.installApk, apkReady && !busy)
        BusyUi.setEnabled(page.buildApk, !busy)
        BusyUi.setEnabled(page.installBuildTools, !busy)
        BusyUi.setEnabled(page.reviseBuild, !busy && state.logText.isNotBlank())
        paintBusy()
        if (nearBottom && state.logText.isNotBlank()) {
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun renderBuildHistory() {
        val key = "${WorkspaceStore.current().id}:${BuildHistory.version}:$buildHistoryOpen"
        if (key == shownHistory) return
        shownHistory = key
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
            val row = RowCardBinding.inflate(activity.layoutInflater, list, false)
            row.cardTitle.text = (if (entry.ok) "● OK   " else "● FAIL ") + entry.repo.ifBlank { "—" }
            row.cardTitle.setTextColor(color(if (entry.ok) R.color.bid else R.color.ask))
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
        val text = TextView(activity).apply {
            this.text = BuildHistory.log(entry).ifBlank { "(empty log)" }
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(color(R.color.tape_ink))
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val scroll = android.widget.ScrollView(activity).apply { addView(text) }
        val stamp = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(entry.started))
        AlertDialog.Builder(activity)
            .setTitle("${entry.repo.ifBlank { "Build" }} · $stamp")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Delete") { _, _ ->
                BuildHistory.delete(entry)
                shownHistory = ""
                renderBuildHistory()
            }
            .show()
    }

    private fun renderSavedApks() {
        val files = ApkLibrary.list(activity)
        if (feature.state.value.lastApkPath?.let { File(it).isFile } != true) {
            feature.setLastApk(files.firstOrNull()?.absolutePath)
        }
        page.savedCount.text = files.size.toString()
        page.savedChevron.rotation = if (savedApksOpen) 90f else 0f
        page.savedScroll.visibility = if (savedApksOpen && files.isNotEmpty()) View.VISIBLE else View.GONE
        val key = files.joinToString { "${it.absolutePath}:${it.length()}" }
        val list = page.savedApks
        val expectedChildren = if (files.isEmpty()) 0 else files.size * 2 - 1
        if (key == shownApks && list.childCount == expectedChildren) return
        shownApks = key
        list.removeAllViews()
        val density = activity.resources.displayMetrics.density
        page.savedScroll.layoutParams = page.savedScroll.layoutParams.apply {
            height = if (files.size > 3) (180 * density).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        for ((index, file) in files.withIndex()) {
            if (index > 0) {
                list.addView(
                    View(activity).apply { setBackgroundColor(color(R.color.line)) },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        density.toInt().coerceAtLeast(1),
                    ),
                )
            }
            val row = RowApkBinding.inflate(activity.layoutInflater, list, false)
            row.apkName.text = file.name
            row.apkMeta.text = "${stamp.format(Date(file.lastModified()))} · ${file.length() / 1024} KB"
            row.apkInstall.setOnClickListener {
                feature.setLastApk(file.absolutePath)
                installBuiltApk(file)
            }
            row.apkDelete.setOnClickListener { confirmDeleteApk(file) }
            list.addView(row.root)
        }
    }

    private fun confirmDeleteApk(file: File) {
        AlertDialog.Builder(activity)
            .setMessage("Delete ${file.name}?")
            .setPositiveButton("Delete") { _, _ ->
                if (file.absolutePath == feature.state.value.lastApkPath) {
                    feature.setLastApk(null)
                }
                file.delete()
                shownApks = ""
                onExternalUpdate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun installBuiltApk(file: File?) {
        if (file == null || !file.isFile) {
            feature.appendUserMessage("APK is missing. Press Build APK again.")
            return
        }
        val err = ApkInstaller.install(activity, file, label = file.name)
        if (err != null) feature.appendUserMessage(err)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
