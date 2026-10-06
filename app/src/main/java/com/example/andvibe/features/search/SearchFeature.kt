package com.example.andvibe.features.search

import android.content.Context
import com.example.andvibe.DebugLog
import com.example.andvibe.FossFeed
import com.example.andvibe.FossSearch
import com.example.andvibe.ProjectStore
import com.example.andvibe.Provider
import com.example.andvibe.Tab
import com.example.andvibe.WorkspaceStore
import com.example.andvibe.core.GitClient
import com.example.andvibe.features.FeatureEffects
import com.example.andvibe.tasks.AppDispatchers
import com.example.andvibe.tasks.Res
import com.example.andvibe.tasks.TaskRunner
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SearchFeature(
    private val app: Context,
    private val tasks: TaskRunner,
    private val dispatchers: AppDispatchers,
    private val session: com.example.andvibe.ProjectSession,
    private val log: (String) -> Unit,
    private val onGitInvalidate: () -> Unit = {},
    private val setPathBanner: (String?) -> Unit,
    private val onProjectChanged: () -> Unit,
    private val onFilesChanged: () -> Unit,
) {
    data class Creds(val provider: Provider, val key: String, val model: String, val base: String)

    data class State(
        val newsTab: Boolean = false,
        val note: String = "",
        val brief: String = "",
        val hits: List<FossSearch.RepoHit> = emptyList(),
        val news: List<FossSearch.NewsHit> = emptyList(),
        val searching: Boolean = false,
        val feedRefreshing: Boolean = false,
        val cloneInProgress: Boolean = false,
        val cachedProjects: List<FossSearch.RepoHit> = emptyList(),
        val feedStatus: String = "",
    )

    sealed interface Effect {
        data class OpenUrl(val url: String) : Effect
        data object GoToFiles : Effect
        data class ShowCloneError(val message: String) : Effect
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val effects = FeatureEffects<Effect>()
    val effectsFlow = effects.events

    fun setNewsTab(news: Boolean) {
        _state.update { it.copy(newsTab = news) }
        if (news) refreshFeedIfStale()
        reloadCache()
    }

    fun reloadCache() {
        _state.update {
            it.copy(
                cachedProjects = FossFeed.projects(app),
                feedStatus = FossFeed.status(app),
            )
        }
    }

    fun refreshFeedIfStale() {
        if (!FossFeed.stale(app) || tasks.holds(Res.FEED)) return
        if (_state.value.brief.isBlank()) {
            _state.update { it.copy(note = "Refreshing the daily FOSS cache…", feedRefreshing = true) }
        }
        tasks.launch("Refreshing feed", Tab.SEARCH, setOf(Res.FEED), dispatchers.io, track = false) {
            val note = runCatching { FossFeed.refresh(app) }.getOrElse { it.message ?: "cache failed" }
            _state.update { s ->
                s.copy(
                    note = if (s.brief.isBlank()) note else s.note,
                    feedRefreshing = false,
                    cachedProjects = FossFeed.projects(app),
                    feedStatus = FossFeed.status(app),
                )
            }
            null
        }
    }

    fun search(queryRaw: String, creds: Creds) {
        if (tasks.holds(Res.FIND)) return
        val query = queryRaw.trim().ifBlank {
            if (_state.value.newsTab) "latest technology and open-source software in the news" else ""
        }
        if (query.isEmpty()) {
            _state.update { it.copy(note = "Say what you want to find.") }
            return
        }
        _state.update {
            it.copy(
                hits = emptyList(),
                news = emptyList(),
                brief = "",
                note = "Searching the web, then checking GitHub, GitLab, Codeberg, and SourceHut…",
                searching = true,
            )
        }
        DebugLog.step("find", "start chars=${query.length} provider=${creds.provider.id}")
        tasks.launch("Searching", Tab.SEARCH, setOf(Res.FIND), dispatchers.io, track = false) {
            val result = try {
                FossSearch.search(
                    query,
                    creds.provider,
                    creds.key,
                    creds.model,
                    creds.base,
                    FossFeed.matching(app, query),
                )
            } catch (t: Throwable) {
                DebugLog.step("find", "fail ${t.javaClass.simpleName}: ${t.message}")
                FossSearch.SearchResult(emptyList(), emptyList(), "", t.message ?: "search failed")
            }
            _state.update {
                it.copy(
                    hits = result.hits,
                    news = result.news,
                    brief = result.brief,
                    note = result.note,
                    searching = false,
                )
            }
            DebugLog.step("find", "done hits=${result.hits.size}")
            null
        }
    }

    fun openNews(url: String) {
        effects.tryEmit(Effect.OpenUrl(url))
    }

    fun noteOpenFailed(message: String) {
        _state.update { it.copy(note = message) }
    }

    fun clone(hit: FossSearch.RepoHit) {
        if (tasks.holds(Res.DOWNLOAD) || tasks.holds(Res.IMPORT)) return
        setPathBanner("Downloading ${hit.name}…")
        _state.update { it.copy(cloneInProgress = true) }
        effects.tryEmit(Effect.GoToFiles)
        log("clone ${hit.cloneUrl}")
        tasks.launch("Cloning ${hit.name}", Tab.FILES, setOf(Res.DOWNLOAD), dispatchers.repo) {
            var failed: String? = null
            try {
                val url = GitClient.normalizeGitUrl(hit.cloneUrl)
                val name = GitClient.repoNameFromUrl(url)
                val dest = File(session.reposDir, name)
                if (dest.isDirectory && !dest.list().isNullOrEmpty()) {
                    WorkspaceStore.include(name)
                    session.cwd = dest.canonicalFile
                    ProjectStore.remember(app, session.cwd)
                    log("already in ~/$name")
                } else {
                    GitClient.clone(url, dest, log)
                    WorkspaceStore.include(name)
                    session.cwd = dest.canonicalFile
                    ProjectStore.remember(app, session.cwd)
                }
                onGitInvalidate()
                session.openFile = null
                onProjectChanged()
            } catch (t: Throwable) {
                failed = "clone failed: ${t.message ?: t.javaClass.simpleName}"
                log(failed)
            } finally {
                setPathBanner(null)
                _state.update { it.copy(cloneInProgress = false) }
                onFilesChanged()
            }
            if (failed != null) {
                effects.tryEmit(Effect.ShowCloneError(failed))
                TaskRunner.Done("Clone failed", failed)
            } else {
                TaskRunner.Done("Clone finished", "${hit.name} is open in Files.")
            }
        }
    }

    fun syncBusy() {
        _state.update {
            it.copy(
                searching = tasks.holds(Res.FIND),
                feedRefreshing = tasks.holds(Res.FEED),
                cloneInProgress = tasks.holds(Res.DOWNLOAD),
            )
        }
    }
}
