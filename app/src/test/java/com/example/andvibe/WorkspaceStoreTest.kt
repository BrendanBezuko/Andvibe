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

        assertTrue(WorkspaceStore.addCard(CardType.FEATURE, CardStatus.TODO, "Ship it", "body"))
        assertEquals(1, WorkspaceStore.cards(CardStatus.TODO).size)

        WorkspaceStore.moveCard(WorkspaceStore.cards(CardStatus.TODO).single().id, CardStatus.IN_PROGRESS)
        assertTrue(WorkspaceStore.cards(CardStatus.TODO).isEmpty())
        assertEquals(CardType.FEATURE, WorkspaceStore.cards(CardStatus.IN_PROGRESS).single().type)

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

    private fun reload() {
        WorkspaceStore.resetForTests()
        WorkspaceStore.init(ApplicationProvider.getApplicationContext<android.content.Context>())
    }

    @Test
    fun legacyColumnsMigrateToTypeAndStatus() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkspaceStore.resetForTests()
        File(ctx.filesDir, "workspaces.json").writeText(
            """{"current":"w","items":[{"id":"w","name":"Old","cards":[
              {"id":"1","column":"idea","title":"A"},
              {"id":"2","column":"bug","title":"B"},
              {"id":"3","column":"solution","title":"C"},
              {"id":"4","column":"completed","title":"D"}]}]}"""
        )
        WorkspaceStore.init(ctx)
        val cards = WorkspaceStore.current().cards.associate { it.title to (it.type to it.status) }
        assertEquals(CardType.FEATURE to CardStatus.TODO, cards["A"])
        assertEquals(CardType.BUG to CardStatus.TODO, cards["B"])
        assertEquals(CardType.SOLUTION to CardStatus.TODO, cards["C"])
        assertEquals(CardType.FEATURE to CardStatus.DONE, cards["D"])
    }

    @Test
    fun cardSyncFieldsProjectAndTombstonesRoundTrip() {
        WorkspaceStore.setProject(ProjectLink("acme", org = true, number = 7))
        WorkspaceStore.addCard(CardType.BUG, CardStatus.HUMAN_TESTING, "Crash", "on open")
        val id = WorkspaceStore.current().cards.single().id
        val issue = CardIssue(12, "https://github.com/acme/app/issues/12", "I_12")
        val remote = CardRemote("PVTI_1", "I_12", "t1|t2", 1234L)
        WorkspaceStore.updateWorkspace(WorkspaceStore.current().id) { ws ->
            ws.copy(cards = ws.cards.map { it.copy(issue = issue, remote = remote) }, deletedRemote = listOf("PVTI_gone"))
        }
        reload()
        val ws = WorkspaceStore.current()
        assertEquals(ProjectLink("acme", true, 7), ws.project)
        assertEquals(listOf("PVTI_gone"), ws.deletedRemote)
        val card = ws.cards.single()
        assertEquals(id, card.id)
        assertEquals(CardType.BUG to CardStatus.HUMAN_TESTING, card.type to card.status)
        assertEquals(issue, card.issue)
        assertEquals(remote, card.remote)
    }

    @Test
    fun editsBumpUpdatedAtButIssueAndNoOpMovesDont() {
        var now = 1_000L
        WorkspaceStore.clock = { now }
        WorkspaceStore.addCard(CardType.FEATURE, CardStatus.TODO, "Search", "")
        val id = WorkspaceStore.current().cards.single().id
        fun updated() = WorkspaceStore.current().cards.single().updatedAt
        assertEquals(1_000L, updated())

        now = 2_000L
        WorkspaceStore.moveCard(id, CardStatus.TODO)
        assertEquals(1_000L, updated())
        WorkspaceStore.moveCard(id, CardStatus.IN_PROGRESS)
        assertEquals(2_000L, updated())

        now = 3_000L
        WorkspaceStore.setIssue(id, CardIssue(1, "u", "n"))
        assertEquals(2_000L, updated())
        WorkspaceStore.updateCard(id, "Search", "", CardType.FEATURE)
        assertEquals(2_000L, updated())
        WorkspaceStore.updateCard(id, "Search v2", "", CardType.BUG)
        assertEquals(3_000L, updated())
    }

    @Test
    fun deletingSyncedCardLeavesTombstoneAndRelinkClearsLinks() {
        WorkspaceStore.setProject(ProjectLink("me", false, 1))
        WorkspaceStore.addCard(CardType.FEATURE, CardStatus.TODO, "Synced", "")
        WorkspaceStore.addCard(CardType.FEATURE, CardStatus.TODO, "Local", "")
        WorkspaceStore.updateWorkspace(WorkspaceStore.current().id) { ws ->
            ws.copy(cards = ws.cards.map { if (it.title == "Synced") it.copy(remote = CardRemote("PVTI_9", "D_9", "s", 0)) else it })
        }
        val cards = WorkspaceStore.current().cards
        WorkspaceStore.deleteCard(cards.first { it.title == "Synced" }.id)
        WorkspaceStore.deleteCard(cards.first { it.title == "Local" }.id)
        assertEquals(listOf("PVTI_9"), WorkspaceStore.current().deletedRemote)

        WorkspaceStore.addCard(CardType.FEATURE, CardStatus.TODO, "Again", "")
        WorkspaceStore.updateWorkspace(WorkspaceStore.current().id) { ws ->
            ws.copy(cards = ws.cards.map { it.copy(remote = CardRemote("PVTI_10", "D_10", "s", 0)) })
        }
        WorkspaceStore.setProject(ProjectLink("me", false, 2))
        assertTrue(WorkspaceStore.current().deletedRemote.isEmpty())
        assertEquals(null, WorkspaceStore.current().cards.single().remote)
    }
}
