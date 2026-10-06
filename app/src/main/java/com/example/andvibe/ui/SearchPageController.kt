package com.example.andvibe.ui

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.andvibe.BusyUi
import com.example.andvibe.FossFeed
import com.example.andvibe.FossSearch
import com.example.andvibe.Provider
import com.example.andvibe.R
import com.example.andvibe.databinding.PageSearchBinding
import com.example.andvibe.databinding.RowFossBinding
import com.example.andvibe.databinding.RowNewsBinding
import com.example.andvibe.features.search.SearchFeature
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

/**
 * Renders [SearchFeature] state and forwards clicks. No domain logic.
 */
class SearchPageController(
    private val page: PageSearchBinding,
    private val feature: SearchFeature,
    private val lifecycleOwner: LifecycleOwner,
    private val inflate: android.view.LayoutInflater,
    private val color: (Int) -> Int,
    private val dp: (Int) -> Int,
    private val currentProvider: () -> Provider,
    private val providerModel: (Provider) -> String,
    private val saveProvider: () -> Unit,
    private val providerCreds: (Provider) -> SearchFeature.Creds,
    private val onGoToFiles: () -> Unit,
    private val onCloneError: (String) -> Unit,
    private val closeEditorIfNeeded: () -> Unit,
) {
    fun start() {
        page.findGo.setOnClickListener { submitSearch() }
        page.findQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                submitSearch()
                true
            } else {
                false
            }
        }
        page.searchTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val news = tab.position == 1
                feature.setNewsTab(news)
                page.findQuery.hint = if (news) "Latest technology" else "What kind of project?"
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        feature.reloadCache()
        feature.refreshFeedIfStale()

        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { feature.state.collect { render(it) } }
                launch {
                    feature.effectsFlow.collect { effect ->
                        when (effect) {
                            is SearchFeature.Effect.OpenUrl -> openUrl(effect.url)
                            SearchFeature.Effect.GoToFiles -> {
                                closeEditorIfNeeded()
                                onGoToFiles()
                            }
                            is SearchFeature.Effect.ShowCloneError -> onCloneError(effect.message)
                        }
                    }
                }
            }
        }
    }

    fun onTabVisible() {
        feature.reloadCache()
        feature.refreshFeedIfStale()
        feature.syncBusy()
    }

    private fun submitSearch() {
        saveProvider()
        val provider = currentProvider()
        feature.search(page.findQuery.text?.toString().orEmpty(), providerCreds(provider))
    }

    private fun render(state: SearchFeature.State) {
        BusyUi.setEnabled(page.findGo, !state.searching)
        val model = providerModel(currentProvider()).ifBlank { currentProvider().defaultModel }
        page.findStatus.text = when {
            state.brief.isNotBlank() -> state.brief
            state.newsTab -> state.note.ifBlank { state.feedStatus }
            state.note.isNotBlank() -> state.note
            else -> "${currentProvider().label} · $model"
        }
        page.findStatus.visibility = View.VISIBLE
        page.findScroll.visibility = View.VISIBLE
        page.findResults.removeAllViews()
        val cloneOk = !state.cloneInProgress && !state.searching
        if (state.newsTab) {
            if (state.cachedProjects.isNotEmpty()) {
                page.findResults.addView(sectionLabel("This week"))
                for (hit in state.cachedProjects) page.findResults.addView(repoRow(hit, cloneOk))
            }
            var group = ""
            for (channel in FossFeed.channels) {
                if (channel.group != group) {
                    group = channel.group
                    page.findResults.addView(sectionLabel(group))
                }
                val row = RowNewsBinding.inflate(inflate, page.findResults, false)
                row.newsTitle.text = channel.name
                row.newsMeta.text = channel.detail
                row.newsSummary.visibility = View.GONE
                row.root.setOnClickListener { feature.openNews(channel.url) }
                page.findResults.addView(row.root)
            }
            if (state.news.isNotEmpty()) {
                page.findResults.addView(sectionLabel("From the web"))
                for (story in state.news) {
                    val row = RowNewsBinding.inflate(inflate, page.findResults, false)
                    row.newsTitle.text = story.title
                    row.newsMeta.text = story.source.ifBlank { hostOf(story.url) }
                    row.newsSummary.text = story.summary
                    row.root.setOnClickListener { feature.openNews(story.url) }
                    val repo = story.repo
                    if (repo != null) {
                        row.newsClone.visibility = View.VISIBLE
                        row.newsClone.text = "Clone ${repo.name}"
                        BusyUi.setEnabled(row.newsClone, cloneOk)
                        row.newsClone.setOnClickListener { if (cloneOk) feature.clone(repo) }
                    }
                    page.findResults.addView(row.root)
                }
            }
        } else {
            for (hit in state.hits) page.findResults.addView(repoRow(hit, cloneOk))
        }
    }

    private fun sectionLabel(text: String): TextView {
        return TextView(page.root.context).apply {
            this.text = text
            setTextColor(color(R.color.muted))
            textSize = 12f
            setPadding(0, dp(14), 0, dp(4))
        }
    }

    private fun repoRow(hit: FossSearch.RepoHit, cloneOk: Boolean): View {
        val row = RowFossBinding.inflate(inflate, null, false)
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
        row.root.setOnClickListener { if (cloneOk) feature.clone(hit) }
        return row.root
    }

    private fun openUrl(url: String) {
        try {
            page.root.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            feature.noteOpenFailed(t.message ?: "could not open the story")
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
}
