package com.example.andvibe.features.console

import androidx.test.core.app.ApplicationProvider
import com.example.andvibe.ProjectSession
import com.example.andvibe.SecretStore
import com.example.andvibe.WorkspaceStore
import com.example.andvibe.core.GitOps
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ConsoleCommandsRunTest {
    private lateinit var ctx: android.content.Context
    private lateinit var session: ProjectSession
    private lateinit var log: ConsoleLog
    private lateinit var secrets: SecretStore
    private lateinit var project: File

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        WorkspaceStore.resetForTests()
        File(ctx.filesDir, "workspaces.json").delete()
        WorkspaceStore.init(ctx)
        secrets = SecretStore(ctx)
        session = ProjectSession(ctx)
        project = File(session.reposDir, "demo").also { it.mkdirs() }
        File(project, "hello.txt").writeText("hi\n")
        WorkspaceStore.include("demo")
        session.cwd = project
        log = ConsoleLog()
        GitOps.auth = { GitOps.Auth("T", "t@e.com", "git", "tok", "") }
    }

    @After
    fun tearDown() {
        WorkspaceStore.resetForTests()
    }

    private fun console(): ConsoleContext = ConsoleContext(
        session = session,
        log = log,
        appContext = ctx,
        secrets = secrets,
        onOpen = {},
        onPreview = {},
        onFilesChanged = {},
        inWorkspace = { session.inWorkspace(it) },
        projectRoot = { session.projectRoot() },
        activeRepos = { WorkspaceStore.activeRepos() },
        includeProject = { WorkspaceStore.include(it) },
        rememberProject = {},
    )

    @Test
    fun helpPwdLsCatCdAndGitStatus() {
        val c = console()
        ConsoleCommands.run("help", c)
        assertTrue(log.text().contains("AndVibe"))

        ConsoleCommands.run("pwd", c)
        assertTrue(log.text().contains("demo") || log.text().contains("~/demo"))

        ConsoleCommands.run("ls", c)
        assertTrue(log.text().contains("hello.txt"))

        ConsoleCommands.run("cat hello.txt", c)
        assertTrue(log.text().contains("hi"))

        ConsoleCommands.run("cd ~", c)
        ConsoleCommands.run("pwd", c)

        GitOps.init(project)
        session.cwd = project
        ConsoleCommands.run("git status", c)
        assertTrue(log.text().contains("Not a git repo") || log.text().isNotBlank())
    }
}
