package com.example.andvibe

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand.ResetType
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.HttpTransport
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

object GitOps {
    var authorName = "AndVibe"
    var authorEmail = "andvibe@local"
    var remoteUser = ""
    var remoteToken = ""

    data class Change(
        val path: String,
        val code: String,
        val staged: Boolean,
        val unstaged: Boolean
    ) {
        val label: String get() = "$code $path"
    }

    data class Snapshot(
        val branch: String,
        val summary: String,
        val changes: List<Change>,
        val isRepo: Boolean
    )

    fun snapshot(start: File, repos: File): Snapshot {
        if (start.canonicalFile == repos.canonicalFile) {
            return Snapshot("", "Open a project from Files.", emptyList(), false)
        }
        val root = RepoFiles.gitRoot(start, repos)
            ?: return Snapshot("", "Not a git repo. Tap Init, or open a folder that has .git.", emptyList(), false)
        return try {
            prepare()
            Git.open(root).use { git -> readSnapshot(git) }
        } catch (t: Throwable) {
            Snapshot("", fail("status", t), emptyList(), false)
        }
    }

    fun status(start: File, repos: File): String {
        val snap = snapshot(start, repos)
        if (!snap.isRepo) return snap.summary
        if (snap.changes.isEmpty()) return "${snap.branch}\n${snap.summary}"
        return buildString {
            append(snap.branch).append('\n')
            append(snap.summary).append('\n')
            snap.changes.take(40).forEach { append(it.label).append('\n') }
            if (snap.changes.size > 40) append("…\n")
        }.trimEnd()
    }

    fun stage(start: File, repos: File, path: String): String {
        if (path == ".") return stageAll(start, repos)
        val root = repo(start, repos)
        val rel = cleanPath(root, path)
        return try {
            prepare()
            Git.open(root).use { git ->
                git.add().addFilepattern(rel).call()
                git.add().setUpdate(true).addFilepattern(rel).call()
            }
            "staged $rel"
        } catch (t: Throwable) {
            fail("stage", t)
        }
    }

    fun stageAll(start: File, repos: File): String {
        val root = repo(start, repos)
        return try {
            prepare()
            Git.open(root).use { git ->
                git.add().addFilepattern(".").call()
                git.add().setUpdate(true).addFilepattern(".").call()
            }
            "staged all changes"
        } catch (t: Throwable) {
            fail("stage", t)
        }
    }

    fun unstage(start: File, repos: File, path: String): String {
        val root = repo(start, repos)
        val rel = cleanPath(root, path)
        return try {
            prepare()
            Git.open(root).use { git ->
                git.reset().setMode(ResetType.MIXED).addPath(rel).call()
            }
            "unstaged $rel"
        } catch (t: Throwable) {
            fail("unstage", t)
        }
    }

    fun unstageAll(start: File, repos: File): String {
        val root = repo(start, repos)
        return try {
            prepare()
            Git.open(root).use { git ->
                git.reset().setMode(ResetType.MIXED).call()
            }
            "unstaged"
        } catch (t: Throwable) {
            fail("unstage", t)
        }
    }

    fun discard(start: File, repos: File, path: String): String {
        val root = repo(start, repos)
        val rel = cleanPath(root, path)
        if (rel == ".git" || rel.startsWith(".git/")) error("can't discard .git")
        val file = File(root, rel)
        val probe = if (file.exists()) file else file.parentFile ?: root
        RepoFiles.ensureInside(root, probe)
        return try {
            prepare()
            Git.open(root).use { git ->
                val status = git.status().call()
                val untracked = rel in status.untracked ||
                    status.untrackedFolders.any { rel == it || rel.startsWith("$it/") }
                if (untracked) {
                    when {
                        file.isDirectory -> file.deleteRecursively()
                        file.isFile -> file.delete()
                        else -> error("nothing to discard")
                    }
                    return@use "discarded $rel"
                }
                val head = git.repository.resolve("HEAD")
                if (head != null) {
                    runCatching {
                        git.checkout().setForced(true).setStartPoint("HEAD").addPath(rel).call()
                    }
                }
                runCatching { git.reset().setMode(ResetType.MIXED).addPath(rel).call() }
                if (!inHead(git, rel) && file.isFile) file.delete()
                "discarded $rel"
            }
        } catch (t: Throwable) {
            fail("discard", t)
        }
    }

    fun commit(start: File, repos: File, message: String): String {
        val text = message.trim()
        if (text.isEmpty()) return "Write a commit message."
        val root = repo(start, repos)
        val name = authorName.ifBlank { "AndVibe" }
        val email = authorEmail.ifBlank { "andvibe@local" }
        return try {
            prepare()
            Git.open(root).use { git ->
                val config = git.repository.config
                config.setString("user", null, "name", name)
                config.setString("user", null, "email", email)
                config.save()
                val status = git.status().call()
                if (status.conflicting.isNotEmpty()) return@use "resolve conflicts before committing"
                val staged = status.added.isNotEmpty() || status.changed.isNotEmpty() || status.removed.isNotEmpty()
                val dirty = staged || status.modified.isNotEmpty() || status.missing.isNotEmpty() ||
                    status.untracked.isNotEmpty() || status.untrackedFolders.isNotEmpty()
                if (!dirty) return@use "nothing to commit"
                if (!staged) {
                    git.add().addFilepattern(".").call()
                    git.add().setUpdate(true).addFilepattern(".").call()
                }
                val rev = git.commit()
                    .setMessage(text)
                    .setAuthor(name, email)
                    .setCommitter(name, email)
                    .call()
                "committed ${rev.name.take(7)} ${rev.shortMessage}"
            }
        } catch (t: Throwable) {
            fail("commit", t)
        }
    }

    fun diff(start: File, repos: File, path: String?, staged: Boolean): String {
        val root = repo(start, repos)
        val rel = path?.let { cleanPath(root, it) }
        return try {
            prepare()
            Git.open(root).use { git ->
                val out = ByteArrayOutputStream()
                val cmd = git.diff().setOutputStream(out)
                if (staged) cmd.setCached(true)
                if (rel != null) cmd.setPathFilter(PathFilter.create(rel))
                cmd.call()
                var text = out.toString(Charsets.UTF_8.name())
                if (text.isBlank() && rel != null && !staged) {
                    val file = File(root, rel)
                    text = when {
                        file.isDirectory -> "folder $rel"
                        file.isFile && RepoFiles.looksBinary(file) -> "binary file"
                        file.isFile -> "new file $rel\n\n" + file.readText().take(60_000)
                        else -> "no changes"
                    }
                }
                if (text.isBlank()) text = "no changes"
                if (text.length > 60_000) text = text.take(60_000) + "\n… truncated"
                text
            }
        } catch (t: Throwable) {
            fail("diff", t)
        }
    }

    fun history(start: File, repos: File): String {
        val root = repo(start, repos)
        return try {
            prepare()
            Git.open(root).use { git ->
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                val text = buildString {
                    var n = 0
                    for (commit in git.log().setMaxCount(40).call()) {
                        n++
                        append(commit.name.take(7))
                        append(' ')
                        append(fmt.format(commit.authorIdent.getWhen()))
                        append(' ')
                        append(commit.shortMessage)
                        append('\n')
                    }
                    if (n == 0) append("no commits")
                }
                text.trimEnd()
            }
        } catch (t: Throwable) {
            fail("log", t)
        }
    }

    fun branches(start: File, repos: File): List<String> {
        val root = repo(start, repos)
        prepare()
        return Git.open(root).use { git ->
            val current = git.repository.branch
            git.branchList().call().map { ref ->
                ref.name.removePrefix("refs/heads/")
            }.sortedWith(compareBy({ it != current }, { it.lowercase() }))
        }
    }

    fun branchReport(start: File, repos: File): String {
        return try {
            val root = repo(start, repos)
            prepare()
            Git.open(root).use { git ->
                val current = git.repository.branch
                val names = git.branchList().call().map { it.name.removePrefix("refs/heads/") }
                if (names.isEmpty()) "no branches"
                else names.joinToString("\n") { if (it == current) "* $it" else "  $it" }
            }
        } catch (t: Throwable) {
            fail("branch", t)
        }
    }

    fun checkout(start: File, repos: File, name: String): String {
        val branch = branchName(name)
        val root = repo(start, repos)
        return try {
            prepare()
            Git.open(root).use { git ->
                git.checkout().setName(branch).call()
                "on ${git.repository.branch}"
            }
        } catch (t: Throwable) {
            fail("checkout", t)
        }
    }

    fun createBranch(start: File, repos: File, name: String): String {
        val branch = branchName(name)
        val root = repo(start, repos)
        return try {
            prepare()
            Git.open(root).use { git ->
                git.checkout().setCreateBranch(true).setName(branch).call()
                "on ${git.repository.branch}"
            }
        } catch (t: Throwable) {
            fail("checkout", t)
        }
    }

    fun init(dir: File): String {
        if (File(dir, ".git").exists()) return "already a git repo"
        return try {
            prepare()
            Git.init().setDirectory(dir).call().close()
            "initialized ${dir.name}"
        } catch (t: Throwable) {
            fail("init", t)
        }
    }

    fun pull(start: File, repos: File): String = network(start, repos, "pull") { git ->
        val cmd = git.pull()
        credentials()?.let { cmd.setCredentialsProvider(it) }
        val result = cmd.call()
        if (result.isSuccessful) "pull ok" else "pull failed"
    }

    fun fetch(start: File, repos: File): String = network(start, repos, "fetch") { git ->
        val cmd = git.fetch().setRemote("origin")
        credentials()?.let { cmd.setCredentialsProvider(it) }
        val result = cmd.call()
        val updates = result.getTrackingRefUpdates()
        if (updates.isEmpty()) "fetch ok"
        else updates.joinToString("\n") { "${it.getLocalName()} ${it.getResult()}" }
    }

    fun push(start: File, repos: File): String {
        if (remoteToken.isBlank()) {
            return "Add an HTTPS token in Git, Account. GitHub wants a personal access token."
        }
        return network(start, repos, "push") { git ->
            val url = git.repository.config.getString("remote", "origin", "url")
            if (url.isNullOrBlank()) return@network "no origin remote. git remote add origin https://…"
            val branch = git.repository.branch ?: return@network "detached HEAD"
            val creds = credentials() ?: return@network "Add an HTTPS token in Git, Account."
            val results = git.push()
                .setRemote("origin")
                .setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch"))
                .setCredentialsProvider(creds)
                .call()
            val lines = mutableListOf<String>()
            for (result in results) {
                for (update in result.getRemoteUpdates()) {
                    lines.add("${update.getSrcRef() ?: branch} ${update.getStatus()}")
                }
                if (result.getMessages().isNotBlank()) lines.add(result.getMessages().trim())
            }
            if (lines.isEmpty()) "push ok" else lines.joinToString("\n")
        }
    }

    fun remoteSummary(start: File, repos: File): String {
        val root = repo(start, repos)
        return try {
            prepare()
            Git.open(root).use { git ->
                val list = git.remoteList().call()
                if (list.isEmpty()) "no remotes"
                else list.joinToString("\n") { remote ->
                    val url = remote.getURIs().firstOrNull()?.toString().orEmpty()
                    "${remote.name} $url".trim()
                }
            }
        } catch (t: Throwable) {
            fail("remote", t)
        }
    }

    fun addRemote(start: File, repos: File, name: String, url: String): String {
        val remote = name.trim()
        if (remote.isEmpty() || remote.any { it.isWhitespace() }) error("bad remote name")
        val gitUrl = GitClient.normalizeGitUrl(url)
        val root = repo(start, repos)
        return try {
            prepare()
            Git.open(root).use { git ->
                git.remoteAdd().setName(remote).setUri(URIish(gitUrl)).call()
            }
            "added $remote $gitUrl"
        } catch (t: Throwable) {
            fail("remote", t)
        }
    }

    private fun network(start: File, repos: File, action: String, block: (Git) -> String): String {
        val root = repo(start, repos)
        return try {
            prepare()
            Git.open(root).use { git -> block(git) }
        } catch (t: Throwable) {
            fail(action, t)
        }
    }

    private fun readSnapshot(git: Git): Snapshot {
        val branch = git.repository.branch ?: "HEAD"
        val status = git.status().call()
        val tracking = runCatching { BranchTrackingStatus.of(git.repository, branch) }.getOrNull()
        val remote = git.repository.config.getString("remote", "origin", "url")
        val track = when {
            tracking == null -> "no upstream"
            else -> "ahead ${tracking.getAheadCount()}, behind ${tracking.getBehindCount()}"
        }
        val paths = linkedSetOf<String>()
        paths.addAll(status.added)
        paths.addAll(status.changed)
        paths.addAll(status.removed)
        paths.addAll(status.modified)
        paths.addAll(status.missing)
        paths.addAll(status.untracked)
        paths.addAll(status.untrackedFolders)
        paths.addAll(status.conflicting)
        val changes = paths.map { path ->
            val untracked = path in status.untracked || path in status.untrackedFolders
            val x = when {
                path in status.conflicting -> 'U'
                path in status.added -> 'A'
                path in status.removed -> 'D'
                path in status.changed -> 'M'
                else -> ' '
            }
            val y = when {
                path in status.conflicting -> 'U'
                untracked -> '?'
                path in status.missing -> 'D'
                path in status.modified -> 'M'
                else -> ' '
            }
            val code = if (untracked && x == ' ') "??" else "$x$y"
            Change(
                path = path,
                code = code,
                staged = x != ' ',
                unstaged = y != ' ' || untracked
            )
        }.sortedBy { it.path }
        val shown = if (changes.size > 300) changes.take(300) else changes
        val stagedCount = changes.count { it.staged }
        val unstagedCount = changes.count { it.unstaged }
        val summary = buildString {
            if (changes.isEmpty()) append("clean") else append("$stagedCount staged, $unstagedCount unstaged")
            if (changes.size > shown.size) append(" · ${changes.size - shown.size} more")
            append(" · ").append(track)
            if (!remote.isNullOrBlank()) append("\n").append(remote)
        }
        return Snapshot(branch, summary, shown, true)
    }

    private fun inHead(git: Git, rel: String): Boolean {
        val head = git.repository.resolve("HEAD") ?: return false
        RevWalk(git.repository).use { walk ->
            val commit = walk.parseCommit(head)
            val tree = TreeWalk.forPath(git.repository, rel, commit.tree) ?: return false
            tree.close()
            return true
        }
    }

    private fun repo(start: File, repos: File): File {
        return RepoFiles.gitRoot(start, repos) ?: error("no .git directory")
    }

    private fun cleanPath(root: File, raw: String): String {
        val rel = raw.trim().removePrefix("./").replace('\\', '/')
        if (rel.isEmpty() || rel == ".") error("bad path")
        if (rel.split('/').any { it.isBlank() || it == ".." }) error("bad path")
        val file = File(root, rel)
        val probe = if (file.exists()) file else file.parentFile ?: root
        RepoFiles.ensureInside(root, probe)
        return rel
    }

    private fun branchName(raw: String): String {
        val name = raw.trim()
        if (name.isEmpty() ||
            name.startsWith("-") ||
            name.contains("..") ||
            name.contains(" ") ||
            name.contains("~") ||
            name.contains("^") ||
            name.contains(":") ||
            name.contains("\\")
        ) {
            error("bad branch name")
        }
        return name
    }

    private fun credentials(): UsernamePasswordCredentialsProvider? {
        val token = remoteToken
        if (token.isBlank()) return null
        return UsernamePasswordCredentialsProvider(remoteUser.ifBlank { "git" }, token)
    }

    private fun prepare() {
        System.setProperty("jgit.fs.useFileAttributesCache", "false")
        HttpTransport.setConnectionFactory(JDKHttpConnectionFactory())
    }

    private fun fail(action: String, t: Throwable): String {
        return "$action failed: ${t.message ?: t.javaClass.simpleName}"
    }
}
