package com.example.andvibe

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class WorkspaceStoreTest {
    @Before
    fun setUp() {
        WorkspaceStore.resetForTests()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(ctx.filesDir, "workspaces.json").delete()
        WorkspaceStore.init(ctx)
    }

    @After
    fun tearDown() {
        WorkspaceStore.resetForTests()
    }

    @Test
    fun columnFromFallsBackToIdea() {
        assertEquals(WorkspaceStore.Column.BUG, WorkspaceStore.Column.from("bug"))
        assertEquals(WorkspaceStore.Column.IDEA, WorkspaceStore.Column.from("nope"))
    }

    @Test
    fun priceTextFormatsUsd() {
        assertEquals("$0.0001", WorkspaceStore.priceText(100))
        assertEquals("$1.50", WorkspaceStore.priceText(1_500_000))
        assertEquals("$100", WorkspaceStore.priceText(100_000_000))
    }

    @Test
    fun createSelectDeleteAndCards() {
        val first = WorkspaceStore.current().id
        val second = WorkspaceStore.create("Alpha")
        assertEquals(second, WorkspaceStore.current().id)
        assertEquals("Alpha", WorkspaceStore.current().name)
        assertTrue(WorkspaceStore.select(first))
        assertTrue(WorkspaceStore.select(second))

        assertTrue(WorkspaceStore.addCard("idea", "Ship it", "body"))
        assertEquals(1, WorkspaceStore.cards(WorkspaceStore.Column.IDEA).size)

        WorkspaceStore.moveCard(WorkspaceStore.cards(WorkspaceStore.Column.IDEA).single().id, "bug")
        assertTrue(WorkspaceStore.cards(WorkspaceStore.Column.IDEA).isEmpty())
        assertEquals(1, WorkspaceStore.cards(WorkspaceStore.Column.BUG).size)

        assertTrue(WorkspaceStore.delete(first))
        assertEquals(1, WorkspaceStore.workspaces().size)
        assertFalse(WorkspaceStore.delete(WorkspaceStore.current().id))
    }

    @Test
    fun includeRepoAndContains() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repos = File(ctx.filesDir, "repos")
        val proj = File(repos, "demo").also { it.mkdirs() }
        WorkspaceStore.include("demo")
        assertTrue(WorkspaceStore.contains(proj))
        assertFalse(WorkspaceStore.contains(File(repos, "other").also { it.mkdirs() }))
    }

    @Test
    fun deleteDownloadedRemovesFolderAndAllWorkspaceRefs() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repos = File(ctx.filesDir, "repos")
        val demo = File(repos, "demo").also { it.mkdirs() }
        File(demo, "README.md").writeText("hi")
        WorkspaceStore.include("demo")
        WorkspaceStore.create("Other")
        WorkspaceStore.include("demo")

        assertTrue(WorkspaceStore.deleteDownloaded("demo"))
        assertFalse(demo.exists())
        assertFalse(WorkspaceStore.downloaded().contains("demo"))
        for (ws in WorkspaceStore.workspaces()) {
            assertFalse(ws.repos.contains("demo"))
        }
        assertFalse(WorkspaceStore.deleteDownloaded(".."))
        assertFalse(WorkspaceStore.deleteDownloaded("nope/../escape"))
    }

    @Test
    fun addUseTracksCost() {
        val before = WorkspaceStore.current().costMicros
        WorkspaceStore.addUse("claude-sonnet", 1_000_000, 1_000_000)
        assertTrue(WorkspaceStore.current().costMicros > before)
        assertEquals(1_000_000L, WorkspaceStore.current().inputTokens)
    }
}
