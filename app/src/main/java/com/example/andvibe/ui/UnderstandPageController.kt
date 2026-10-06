package com.example.andvibe.ui

import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.andvibe.BusyUi
import com.example.andvibe.R
import com.example.andvibe.UnderstandDoc
import com.example.andvibe.WorkspaceStore
import com.example.andvibe.databinding.PageUnderstandBinding
import com.example.andvibe.features.understand.UnderstandFeature
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Renders [UnderstandFeature] state and forwards clicks. No domain logic.
 */
class UnderstandPageController(
    private val activity: AppCompatActivity,
    private val page: PageUnderstandBinding,
    private val feature: UnderstandFeature,
    private val color: (Int) -> Int,
    private val cssColor: (Int) -> String,
    private val vibeRoot: () -> File?,
    private val loadMermaid: () -> ByteArray?,
    private val beforeTrackedRun: () -> Unit,
    private val ensureProject: (File) -> Unit,
    private val openProject: (File) -> Unit,
    private val openWorkspace: () -> Unit,
    private val saveProvider: () -> Unit,
    private val providerCreds: () -> UnderstandFeature.Creds,
    private val onSaved: (File) -> Unit,
    private val paintBusy: () -> Unit,
) {
    private var renderedMarkdown = ""
    private var mermaidJs: ByteArray? = null

    fun start() {
        setupWeb(page.understandWeb)
        page.understandRun.setOnClickListener { onRunClick() }
        page.understandSave.setOnClickListener {
            val root = vibeRoot() ?: run {
                feature.requestPickRepo()
                return@setOnClickListener
            }
            feature.save(root)
        }
        page.understandSource.setOnClickListener { feature.toggleSource() }
        page.understandRepoPick.setOnClickListener { pickRepoClick() }
        page.understandRepoBar.setOnClickListener { pickRepoClick() }

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { feature.state.collect { render(it) } }
                launch {
                    feature.effectsFlow.collect { effect ->
                        when (effect) {
                            UnderstandFeature.Effect.PickRepo -> showRepoPicker()
                            is UnderstandFeature.Effect.Saved -> onSaved(effect.root)
                        }
                    }
                }
            }
        }
        vibeRoot()?.let { feature.loadSaved(it) }
    }

    fun onTabVisible() {
        feature.syncBusy()
        vibeRoot()?.let { feature.loadSaved(it) }
    }

    fun onProjectChanged(wasBusy: Boolean) {
        if (!wasBusy) {
            feature.clearForProjectChange()
            vibeRoot()?.let { feature.loadSaved(it) }
        }
    }

    private fun onRunClick() {
        if (feature.state.value.running) {
            feature.stop()
            return
        }
        val root = vibeRoot()
        if (root == null) {
            feature.requestPickRepo()
            return
        }
        beforeTrackedRun()
        ensureProject(root)
        saveProvider()
        val focus = page.understandFocus.text?.toString()?.trim().orEmpty()
        feature.run(root, focus, providerCreds())
    }

    private fun pickRepoClick() {
        if (feature.state.value.running) return
        showRepoPicker()
    }

    private fun showRepoPicker() {
        if (feature.state.value.running) return
        val dirs = WorkspaceStore.activeRepos()
        if (dirs.isEmpty()) {
            openWorkspace()
            return
        }
        val current = vibeRoot()?.name
        AlertDialog.Builder(activity)
            .setTitle("Repo to understand")
            .setSingleChoiceItems(
                dirs.map { it.name }.toTypedArray(),
                dirs.indexOfFirst { it.name == current },
            ) { dialog, which ->
                dialog.dismiss()
                if (dirs[which].name != current) {
                    openProject(dirs[which])
                    feature.clearForProjectChange()
                    vibeRoot()?.let { feature.loadSaved(it) }
                }
            }
            .setNeutralButton("Edit repos") { _, _ -> openWorkspace() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupWeb(web: WebView) {
        web.setBackgroundColor(color(R.color.bg))
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
                interceptAsset(request)
        }
    }

    private fun interceptAsset(request: WebResourceRequest): android.webkit.WebResourceResponse? {
        val uri = request.url ?: return null
        if (uri.host != UnderstandDoc.HOST) return null
        val name = uri.lastPathSegment ?: return UnderstandDoc.missing()
        if (name != "mermaid.min.js") return UnderstandDoc.missing()
        val bytes = mermaidJs ?: loadMermaid()?.also { mermaidJs = it } ?: return UnderstandDoc.missing()
        return UnderstandDoc.asset(name, bytes, "application/javascript")
    }

    private fun docPalette() = UnderstandDoc.Palette(
        bg = cssColor(R.color.bg),
        panel = cssColor(R.color.panel),
        raised = cssColor(R.color.raised),
        ink = cssColor(R.color.ink),
        muted = cssColor(R.color.muted),
        accent = cssColor(R.color.accent),
        line = cssColor(R.color.line),
        code = cssColor(R.color.tape),
        ask = cssColor(R.color.ask),
        quote = cssColor(R.color.quote),
        bid = cssColor(R.color.bid),
    )

    private fun render(state: UnderstandFeature.State) {
        val busy = state.running
        val repo = if (busy) state.repo else vibeRoot()
        page.understandRepo.text = repo?.name ?: "No repo selected"
        page.understandRepo.setTextColor(color(if (repo == null) R.color.muted else R.color.accent))
        BusyUi.setEnabled(page.understandRepoPick, !busy)
        BusyUi.setEnabled(page.understandRepoBar, !busy)

        val text = state.markdown
        val has = text.isNotBlank()
        page.understandEmpty.visibility = if (has) View.GONE else View.VISIBLE
        page.understandSave.isEnabled = has
        BusyUi.setEnabled(page.understandSave, has && !busy)
        page.understandSource.isEnabled = has
        page.understandSource.text = if (state.showSource) "View" else "Source"
        BusyUi.setEnabled(page.understandFocus, !busy)

        if (!has) {
            renderedMarkdown = ""
            page.understandWeb.visibility = View.GONE
            page.understandScroll.visibility = View.GONE
            page.understandOut.text = ""
            if (page.understandWeb.url != null) page.understandWeb.loadUrl("about:blank")
        } else if (state.showSource) {
            page.understandWeb.visibility = View.GONE
            page.understandScroll.visibility = View.VISIBLE
            page.understandOut.text = text
        } else {
            page.understandScroll.visibility = View.GONE
            page.understandWeb.visibility = View.VISIBLE
            if (text != renderedMarkdown) {
                renderedMarkdown = text
                val palette = docPalette()
                page.understandWeb.loadDataWithBaseURL(
                    "https://${UnderstandDoc.HOST}/",
                    UnderstandDoc.page(text, palette),
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        }

        when {
            !busy -> {
                BusyUi.setEnabled(page.understandRun, true)
                page.understandRun.text = "Understand"
                page.understandStatus.text = when {
                    state.statusNote.isNotBlank() -> "● ${state.statusNote.uppercase(Locale.US)}"
                    has -> "● READY"
                    else -> "● READY"
                }
                page.understandStatus.setTextColor(
                    color(
                        if (state.statusNote.contains("fail", true) ||
                            state.statusNote.contains("error", true)
                        ) {
                            R.color.quote
                        } else {
                            R.color.muted
                        },
                    ),
                )
            }
            state.stopping -> {
                BusyUi.setEnabled(page.understandRun, false)
                page.understandRun.text = "Stop"
            }
            else -> {
                BusyUi.setEnabled(page.understandRun, true)
                page.understandRun.text = "Stop"
                page.understandStatus.text =
                    "● ${state.statusNote.ifBlank { "WORKING" }.uppercase(Locale.US)}"
                page.understandStatus.setTextColor(color(R.color.accent))
            }
        }
        paintBusy()
    }
}
