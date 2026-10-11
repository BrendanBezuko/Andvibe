package com.example.andvibe.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitOpsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var repos: File
    private lateinit var project: File
    private val defaultAuth = GitOps.auth

    @Before
    fun setUp() {
        repos = tmp.newFolder("repos")
        project = File(repos, "proj").also { it.mkdirs() }
        GitOps.auth = {
            GitOps.Auth("Test User", "test@example.com", "git", "token", "")
        }
    }

    @After
    fun tearDown() {
        GitOps.auth = defaultAuth
    }

    @Test
    fun changeLabelJoinsCodeAndPath() {
        val change = GitOps.Change("src/a.kt", "M ", staged = true, unstaged = false)
        assertEquals("M  src/a.kt", change.label)
    }

    @Test
    fun isSshUrlDetectsGitAtAndSshScheme() {
        assertTrue(GitOps.isSshUrl("git@github.com:owner/repo.git"))
        assertTrue(GitOps.isSshUrl("ssh://git@github.com/owner/repo"))
        assertFalse(GitOps.isSshUrl("https://github.com/owner/repo.git"))
    }

    @Test
    fun initStageCommitUnstageDiscardSnapshot() {
        assertEquals("initialized proj", GitOps.init(project))
        assertEquals("already a git repo", GitOps.init(project))

        File(project, "README.md").writeText("hello\n")
        var snap = GitOps.snapshot(project, repos)
        assertTrue(snap.isRepo)
        assertTrue(snap.changes.any { it.path == "README.md" && it.code == "??" })

        assertEquals("Nothing is staged. Tap + on a file, or Stage all.", GitOps.commit(project, repos, "msg"))

        assertTrue(GitOps.stage(project, repos, "README.md").contains("staged"))
        snap = GitOps.snapshot(project, repos)
        assertTrue(snap.changes.any { it.path == "README.md" && it.staged })

        val committed = GitOps.commit(project, repos, "add readme")
        assertTrue(committed.startsWith("committed"))
        assertTrue(committed.contains("add readme"))

        File(project, "README.md").writeText("hello\nworld\n")
        File(project, "extra.txt").writeText("x\n")
        assertEquals("staged all changes", GitOps.stageAll(project, repos))
        assertEquals("unstaged extra.txt", GitOps.unstage(project, repos, "extra.txt"))
        assertEquals("discarded extra.txt", GitOps.discard(project, repos, "extra.txt"))
        assertFalse(File(project, "extra.txt").exists())

        snap = GitOps.snapshot(project, repos)
        assertTrue(snap.changes.any { it.path == "README.md" })
        assertTrue(snap.commits.isNotEmpty())
        assertTrue(GitOps.status(project, repos).contains(snap.branch))

        File(project, "README.md").writeText("changed again\n")
        File(project, "junk.txt").writeText("junk\n")
        assertEquals("discarded all changes", GitOps.discardAll(project, repos))
        assertEquals("hello\n", File(project, "README.md").readText())
        assertFalse(File(project, "junk.txt").exists())
        assertTrue(GitOps.snapshot(project, repos).changes.isEmpty())
    }

    @Test
    fun branchTagAndOrigin() {
        GitOps.init(project)
        File(project, "a.txt").writeText("a\n")
        GitOps.stageAll(project, repos)
        GitOps.commit(project, repos, "first")

        assertTrue(GitOps.createBranch(project, repos, "feature").contains("on feature"))
        assertTrue(GitOps.branches(project, repos).contains("feature"))
        assertTrue(GitOps.branchReport(project, repos).contains("* feature"))

        val names = GitOps.branches(project, repos)
        val home = names.first { it == "main" || it == "master" }
        assertTrue(GitOps.checkout(project, repos, home).startsWith("on "))

        assertThrows(IllegalStateException::class.java) {
            GitOps.createBranch(project, repos, "bad name")
        }
        assertThrows(IllegalStateException::class.java) {
            GitOps.createBranch(project, repos, "..evil")
        }

        assertTrue(
            GitOps.createTag(project, repos, "!!bad", "x")
                .contains("Tag names start with"),
        )
        assertEquals("tagged v1.0", GitOps.createTag(project, repos, "v1.0", "release"))
        assertTrue(GitOps.tags(project, repos).any { it.name == "v1.0" })

        assertEquals(
            "origin https://github.com/owner/repo.git",
            GitOps.setOrigin(project, repos, "https://github.com/owner/repo.git"),
        )
        assertEquals(
            "https://github.com/owner/repo.git",
            GitOps.originUrl(project, repos),
        )
        assertEquals("origin has a space", GitOps.setOrigin(project, repos, "https://x.com/a b"))
        assertEquals("origin unchanged", GitOps.setOrigin(project, repos, "  "))
    }

    @Test
    fun pushReportsMissingAuthWithoutNetwork() {
        GitOps.init(project)
        File(project, "a.txt").writeText("a\n")
        GitOps.stageAll(project, repos)
        GitOps.commit(project, repos, "first")
        GitOps.setOrigin(project, repos, "https://github.com/owner/repo.git")

        GitOps.auth = {
            GitOps.Auth("Test", "t@e.com", "git", "", "")
        }
        assertTrue(GitOps.push(project, repos).contains("GitHub token"))

        GitOps.setOrigin(project, repos, "git@github.com:owner/repo.git")
        assertTrue(GitOps.push(project, repos).contains("SSH private key"))
    }

    @Test
    fun snapshotOutsideProjectAsksToOpen() {
        val snap = GitOps.snapshot(repos, repos)
        assertFalse(snap.isRepo)
        assertTrue(snap.summary.contains("Open a project"))
    }

    @Test
    fun commitChatTruncatesSubjectAndCommitsFiles() {
        File(project, "note.txt").writeText("hi\n")
        val long = "word ".repeat(30).trim()
        val result = GitOps.commitChat(project, listOf(File(project, "note.txt")), long)
        assertTrue(result.startsWith("committed"))
        assertTrue(result.contains("..."))
        assertTrue(File(project, ".git").isDirectory)
    }

    @Test
    fun outgoingIsWholeTreeOnFirstPushThenOnlyNewLines() {
        GitOps.init(project)
        File(project, "README.md").writeText("hello\n")
        File(project, ".env").writeText("A=1\n")
        GitOps.stageAll(project, repos)
        GitOps.commit(project, repos, "first")

        val first = GitOps.outgoing(project, repos)
        assertEquals(setOf("README.md", ".env"), first.names.toSet())
        assertEquals(listOf(1 to "hello"), first.files.first { it.path == "README.md" }.lines)

        // Pretend the first commit was pushed: point origin/<branch> at it.
        org.eclipse.jgit.api.Git.open(project).use { git ->
            val branch = git.repository.branch
            val update = git.repository.updateRef("refs/remotes/origin/$branch")
            update.setNewObjectId(git.repository.resolve("HEAD"))
            update.forceUpdate()
        }
        val token = "gh" + "p_" + "aB3dE5fG7hJ9kL1mN3pQ5rS7tU9vW1xY3zA5"
        File(project, "README.md").writeText("hello\nworld\n")
        File(project, "Config.kt").writeText("val a = 1\nval t = \"$token\"\n")
        GitOps.stageAll(project, repos)
        GitOps.commit(project, repos, "second")

        val next = GitOps.outgoing(project, repos)
        assertEquals(setOf("README.md", "Config.kt"), next.names.toSet())
        assertEquals(listOf(2 to "world"), next.files.first { it.path == "README.md" }.lines)
        val findings = GitOps.scanOutgoing(project, repos).getOrThrow()
        assertEquals(listOf("Config.kt" to 2), findings.map { it.path to it.line })
    }
}
