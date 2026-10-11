package com.example.andvibe.features.git

import android.content.Context
import com.example.andvibe.AiClient
import com.example.andvibe.CompanionCli
import com.example.andvibe.DebugLog
import com.example.andvibe.GitIdentity
import com.example.andvibe.ProjectSession
import com.example.andvibe.PromptStore
import com.example.andvibe.Provider
import com.example.andvibe.SecretStore
import com.example.andvibe.Tab
import com.example.andvibe.core.GitOps
import com.example.andvibe.features.FeatureEffects
import com.example.andvibe.tasks.AppDispatchers
import com.example.andvibe.tasks.Res
import com.example.andvibe.tasks.TaskRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class GitFeature(
    private val app: Context,
    private val tasks: TaskRunner,
    private val dispatchers: AppDispatchers,
    private val session: ProjectSession,
    private val secrets: SecretStore,
    private val log: (String) -> Unit,
    private val onFilesChanged: () -> Unit,
) {
    data class Creds(
        val provider: Provider,
        val key: String,
        val model: String,
        val base: String,
        val apiStyle: com.example.andvibe.ApiStyle = com.example.andvibe.ApiStyle.from(provider),
    )

    data class State(
        val busy: Boolean = false,
        val snapshot: GitOps.Snapshot? = null,
        val detail: String? = null,
        val clearCommitMessage: Boolean = false,
    )

    sealed interface Effect {
        data class SetCommitDraft(val text: String) : Effect
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val effects = FeatureEffects<Effect>()
    val effectsFlow = effects.events

    fun syncBusy() {
        _state.update { it.copy(busy = tasks.holds(Res.GIT)) }
    }

    fun invalidate() {
        _state.update { it.copy(snapshot = null, detail = null) }
    }

    fun closeDetail() {
        _state.update { it.copy(detail = null) }
    }

    fun showDetail(text: String) {
        _state.update { it.copy(detail = text) }
    }

    fun ackClearCommitMessage() {
        _state.update { it.copy(clearCommitMessage = false) }
    }

    fun saveCommitDraft(message: String) {
        secrets.saveDraftCommitMessage(message)
    }

    fun commitDraft(): String = secrets.draftCommitMessage()

    fun refresh() {
        if (tasks.holds(Res.GIT)) return
        syncBusy()
        tasks.launch("Git working", Tab.GIT, setOf(Res.GIT), dispatchers.repo, track = false) {
            applySnapshot(loadSnapshot())
            syncBusy()
            null
        }
    }

    /** Refresh snapshot when another subsystem changed files and git is idle. */
    fun refreshSnapshotIfIdle() {
        if (tasks.holds(Res.GIT)) return
        tasks.launch("Git working", Tab.GIT, setOf(Res.GIT), dispatchers.repo, track = false) {
            applySnapshot(loadSnapshot())
            syncBusy()
            null
        }
    }

    fun refreshWithDetailClear() {
        _state.update { it.copy(detail = null) }
        refresh()
    }

    fun stage(path: String) = runGit(clearDetail = true) { GitOps.stage(session.cwd, session.reposDir, path) }

    fun unstage(path: String) = runGit(clearDetail = true) { GitOps.unstage(session.cwd, session.reposDir, path) }

    fun discard(path: String) = runGit(clearDetail = true) { GitOps.discard(session.cwd, session.reposDir, path) }

    fun discardAll() = runGit(clearDetail = true) { GitOps.discardAll(session.cwd, session.reposDir) }

    fun stageAll() = runGit(clearDetail = true) { GitOps.stageAll(session.cwd, session.reposDir) }

    fun unstageAll() = runGit(clearDetail = true) { GitOps.unstageAll(session.cwd, session.reposDir) }

    fun setOrigin(url: String) = runGit("Origin", clearDetail = true) {
        GitOps.setOrigin(session.cwd, session.reposDir, url.trim())
    }

    fun pull() = runGit("Pull", clearDetail = true) { GitOps.pull(session.cwd, session.reposDir) }

    fun push() = runGit("Push", clearDetail = true) { GitOps.push(session.cwd, session.reposDir) }

    fun fetch() = runGit("Fetch", clearDetail = true) { GitOps.fetch(session.cwd, session.reposDir) }

    /**
     * Fork on GitHub via Build Tools `gh`, then point origin at your fork.
     * [repo] is `owner/name`, or blank to fork the current origin.
     *
     * `gh` only accepts `--remote` when forking the cwd repo (no slug). With a
     * slug we fork then set origin ourselves (SSH if a key is saved).
     */
    fun forkRepo(repo: String) {
        val slug = repo.trim().removePrefix("https://github.com/").removePrefix("http://github.com/")
            .removeSuffix(".git").trim('/')
        runGit("Fork", clearDetail = true) {
            if (secrets.gitToken().isBlank()) {
                return@runGit "Add a GitHub token (PAT) in Settings — Fork uses gh with GH_TOKEN."
            }
            if (!CompanionCli.ready(app)) {
                return@runGit "Install Build Tools (Build tab) for gh, then Fork again."
            }
            val args = if (slug.isBlank()) {
                listOf("repo", "fork", "--remote=true")
            } else {
                listOf("repo", "fork", slug)
            }
            val result = CompanionCli.runGh(
                context = app,
                secrets = secrets,
                args = args,
                cwd = session.cwd,
                log = log,
            )
            if (result.code != 0) {
                return@runGit result.output.ifBlank { "gh fork exited ${result.code}" }
            }
            val out = result.output.ifBlank { "forked${if (slug.isBlank()) "" else " $slug"}" }
            if (slug.isNotBlank()) {
                val forkedSlug = resolveForkSlug(slug, result.output)
                    ?: return@runGit "$out\nFork ok, but could not resolve your fork URL — set origin manually."
                val url = if (secrets.gitSsh().isNotBlank()) {
                    "git@github.com:$forkedSlug.git"
                } else {
                    "https://github.com/$forkedSlug.git"
                }
                val note = runCatching {
                    GitOps.setOrigin(session.cwd, session.reposDir, url)
                }.getOrDefault(url)
                return@runGit "$out\n$note"
            }
            // Blank slug: gh --remote already rewrote remotes; prefer SSH if a key is saved.
            if (secrets.gitSsh().isNotBlank()) {
                val origin = GitOps.originUrl(session.cwd, session.reposDir)
                val forkedSlug = GitIdentity.githubSlug(origin)
                if (forkedSlug.isNotBlank() && !GitOps.isSshUrl(origin)) {
                    val ssh = "git@github.com:$forkedSlug.git"
                    val note = runCatching {
                        GitOps.setOrigin(session.cwd, session.reposDir, ssh)
                    }.getOrDefault(ssh)
                    return@runGit "$out\n$note"
                }
            }
            out
        }
    }

    /** Fork owner/name from gh output, or PAT login + source repo name. */
    private fun resolveForkSlug(sourceSlug: String, ghOutput: String): String? {
        val fromOut = Regex("""(?i)https://github\.com/([^/\s]+)/([^/\s]+)""")
            .findAll(ghOutput)
            .map { "${it.groupValues[1]}/${it.groupValues[2].removeSuffix(".git")}" }
            .lastOrNull { !it.equals(sourceSlug, ignoreCase = true) }
        if (fromOut != null) return fromOut
        val login = CompanionCli.runGh(
            context = app,
            secrets = secrets,
            args = listOf("api", "user", "-q", ".login"),
            cwd = session.cwd,
            log = log,
        )
        if (login.code != 0) return null
        val user = login.output.trim().lines().lastOrNull()?.trim().orEmpty()
        if (user.isBlank()) return null
        return "$user/${sourceSlug.substringAfterLast('/')}"
    }

    fun suggestedForkSlug(): String =
        GitIdentity.githubSlug(GitOps.originUrl(session.cwd, session.reposDir))

    /**
     * Open a GitHub pull request via Build Tools `gh`.
     * Blank [title] uses `gh pr create --fill` (commit subjects).
     */
    fun createPullRequest(title: String, body: String) {
        runGit("Pull request", clearDetail = true) {
            if (secrets.gitToken().isBlank()) {
                return@runGit "Add a GitHub token (PAT) in Settings — Create PR uses gh with GH_TOKEN."
            }
            if (!CompanionCli.ready(app)) {
                return@runGit "Install Build Tools (Build tab) for gh, then Create PR again."
            }
            val t = title.trim()
            val args = if (t.isBlank()) {
                listOf("pr", "create", "--fill")
            } else {
                listOf("pr", "create", "--title", t, "--body", body.trim())
            }
            val result = CompanionCli.runGh(
                context = app,
                secrets = secrets,
                args = args,
                cwd = session.cwd,
                log = log,
            )
            if (result.code != 0) {
                return@runGit result.output.ifBlank { "gh pr create exited ${result.code}" }
            }
            result.output.ifBlank { "pull request created" }
        }
    }

    fun initRepo() = runGit(clearDetail = true) {
        val dir = runCatching { session.projectRoot() }.getOrElse {
            return@runGit it.message ?: "Open a project first."
        }
        GitOps.init(dir)
    }

    fun commit(message: String) {
        if (message.isBlank()) return
        saveCommitDraft("")
        runGit(clearDetail = true) {
            val result = GitOps.commit(session.cwd, session.reposDir, message)
            if (result.startsWith("committed")) {
                _state.update { it.copy(clearCommitMessage = true) }
            }
            result
        }
    }

    fun showHistory() = runGit(clearDetail = false) {
        _state.update { it.copy(detail = GitOps.history(session.cwd, session.reposDir)) }
        null
    }

    fun showDiff(path: String, staged: Boolean) = runGit(clearDetail = false) {
        _state.update { it.copy(detail = GitOps.diff(session.cwd, session.reposDir, path, staged)) }
        null
    }

    fun checkout(branch: String) = runGit(clearDetail = true) {
        GitOps.checkout(session.cwd, session.reposDir, branch)
    }

    fun createBranch(name: String) = runGit(clearDetail = true) {
        GitOps.createBranch(session.cwd, session.reposDir, name)
    }

    /** Create an annotated tag; [onDone] runs on the main thread with result text and refreshed tags. */
    fun createRelease(name: String, notes: String, onDone: (String, List<GitOps.TagLine>) -> Unit) {
        if (tasks.holds(Res.GIT)) return
        if (name.isBlank()) {
            log("Release needs a name")
            return
        }
        syncBusy()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        tasks.launch("Create release", Tab.GIT, setOf(Res.GIT), dispatchers.repo, track = true) {
            val text = try {
                GitOps.createTag(session.cwd, session.reposDir, name, notes)
            } catch (t: Throwable) {
                t.message ?: t.javaClass.simpleName
            }
            val tags = runCatching { GitOps.tags(session.cwd, session.reposDir) }.getOrDefault(emptyList())
            applySnapshot(loadSnapshot())
            onFilesChanged()
            main.post {
                log(text)
                onDone(text, tags)
                syncBusy()
            }
            TaskRunner.Done("Release finished", text)
        }
    }

    fun suggestMessage(creds: Creds) {
        if (tasks.holds(Res.GIT)) return
        val snap = _state.value.snapshot
        if (snap == null || !snap.isRepo || snap.changes.isEmpty()) return
        if (creds.key.isBlank() || creds.model.isBlank()) {
            log("Add an API key in Settings, then tap Message.")
            return
        }
        syncBusy()
        val cwd = session.cwd
        val repos = session.reposDir
        tasks.launch("Git working", Tab.GIT, setOf(Res.GIT), dispatchers.repo, track = false) {
            val text = try {
                val files = snap.changes.joinToString("\n") { it.label }
                val diff = GitOps.diff(cwd, repos, null, false).take(4000)
                val staged = GitOps.diff(cwd, repos, null, true).take(2000)
                val raw = AiClient.complete(
                    PromptStore.get(PromptStore.Kind.COMMIT),
                    "Changes:\n$files\n\nUnstaged diff:\n$diff\n\nStaged diff:\n$staged",
                    creds.provider,
                    creds.key,
                    creds.model,
                    creds.base,
                    apiStyle = creds.apiStyle,
                )
                raw.lineSequence().map { it.trim().trim('"') }.firstOrNull { it.isNotEmpty() }.orEmpty()
                    .let { if (it.length <= 72) it else it.take(69).trimEnd() + "..." }
            } catch (t: Throwable) {
                log(t.message ?: "could not write a message")
                ""
            }
            if (text.isNotEmpty()) {
                saveCommitDraft(text)
                effects.tryEmit(Effect.SetCommitDraft(text))
            }
            syncBusy()
            null
        }
    }

    private fun runGit(
        label: String? = null,
        track: Boolean = label != null,
        clearDetail: Boolean = true,
        block: () -> String?,
    ) {
        if (tasks.holds(Res.GIT)) return
        if (clearDetail) {
            _state.update { it.copy(detail = null) }
        }
        syncBusy()
        tasks.launch(
            label ?: "Git working",
            Tab.GIT,
            setOf(Res.GIT),
            dispatchers.repo,
            track = track,
        ) {
            DebugLog.step("git", "start")
            val msg = try {
                block()
            } catch (t: Throwable) {
                DebugLog.step("git", "fail ${t.javaClass.simpleName}: ${t.message}")
                t.message ?: t.javaClass.simpleName
            }
            if (!msg.isNullOrBlank()) {
                DebugLog.step("git", "result ${msg.lineSequence().firstOrNull().orEmpty()}")
                log(msg)
            }
            applySnapshot(loadSnapshot())
            onFilesChanged()
            syncBusy()
            if (label != null) TaskRunner.Done("$label finished", msg.orEmpty()) else null
        }
    }

    private fun loadSnapshot(): GitOps.Snapshot =
        runCatching { GitOps.snapshot(session.cwd, session.reposDir) }
            .getOrElse { GitOps.Snapshot("", it.message ?: "git failed", emptyList(), false) }

    private fun applySnapshot(snap: GitOps.Snapshot) {
        _state.update { it.copy(snapshot = snap) }
    }
}
