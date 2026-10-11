package com.example.andvibe.features.board

import com.example.andvibe.CardRemote
import com.example.andvibe.CardStatus
import com.example.andvibe.CardType
import com.example.andvibe.WorkspaceStore.Card
import com.example.andvibe.features.board.BoardSync.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure planner and merge; GitHub round trips are in GitHubBoardSyncTest. */
class BoardSyncTest {
    private val start = 10_000L

    private fun card(
        id: String,
        title: String = id,
        status: CardStatus = CardStatus.TODO,
        updatedAt: Long = 100L,
        remote: CardRemote? = null,
    ) = Card(id, CardType.FEATURE, status, title, "", updatedAt, remote = remote)

    private fun linked(id: String, item: String, stamp: String = "s1", syncedAt: Long = 500L, updatedAt: Long = 100L, title: String = id) =
        card(id, title = title, updatedAt = updatedAt, remote = CardRemote(item, "D_$item", stamp, syncedAt))

    private fun remote(item: String, title: String, stamp: String = "s1", status: CardStatus? = CardStatus.TODO, updatedAt: Long = 0L) =
        BoardSync.Remote(item, "D_$item", false, title, "", status, CardType.FEATURE, stamp, updatedAt, null)

    @Test
    fun unlinkedCardsAreCreatedRemotely() {
        val plan = BoardSync.plan(listOf(card("a")), emptyList(), emptyList(), start)
        assertEquals(listOf(Op.Create("a")), plan.ops)
    }

    @Test
    fun unchangedOnBothSidesDoesNothing() {
        val plan = BoardSync.plan(listOf(linked("a", "I1")), listOf(remote("I1", "a")), emptyList(), start)
        assertTrue(plan.ops.isEmpty())
        assertEquals(0, plan.pulled)
    }

    @Test
    fun localChangePushesOnlyWhatDiffers() {
        val c = linked("a", "I1", updatedAt = 900L).copy(status = CardStatus.HUMAN_TESTING)
        val plan = BoardSync.plan(listOf(c), listOf(remote("I1", "a")), emptyList(), start)
        assertEquals(listOf(Op.PushStatus("a")), plan.ops)
    }

    @Test
    fun remoteChangeIsPulledAndStampedAtSyncStart() {
        val plan = BoardSync.plan(
            listOf(linked("a", "I1")),
            listOf(remote("I1", "renamed", stamp = "s2", status = CardStatus.DONE)),
            emptyList(),
            start,
        )
        val pulled = plan.cards.single()
        assertEquals("renamed", pulled.title)
        assertEquals(CardStatus.DONE, pulled.status)
        assertEquals(start, pulled.updatedAt)
        assertEquals(1, plan.pulled)
        assertTrue(plan.ops.isEmpty())
    }

    @Test
    fun remoteWithoutMappableStatusKeepsLocalStatus() {
        val c = linked("a", "I1").copy(status = CardStatus.BACK_BURNER)
        val plan = BoardSync.plan(listOf(c), listOf(remote("I1", "x", stamp = "s2", status = null)), emptyList(), start)
        assertEquals(CardStatus.BACK_BURNER, plan.cards.single().status)
    }

    @Test
    fun conflictNewerEditWins() {
        val local = linked("a", "I1", updatedAt = 900L, title = "local")
        val olderRemote = BoardSync.plan(listOf(local), listOf(remote("I1", "remote", stamp = "s2", updatedAt = 800L)), emptyList(), start)
        assertEquals("local", olderRemote.cards.single().title)
        assertEquals(listOf(Op.PushContent("a")), olderRemote.ops)

        val newerRemote = BoardSync.plan(listOf(local), listOf(remote("I1", "remote", stamp = "s2", updatedAt = 950L)), emptyList(), start)
        assertEquals("remote", newerRemote.cards.single().title)
        assertTrue(newerRemote.ops.isEmpty())
    }

    @Test
    fun remoteDeletionRemovesCleanCardButRecreatesEditedOne() {
        val clean = linked("a", "I1")
        val edited = linked("b", "I2", updatedAt = 900L)
        val plan = BoardSync.plan(listOf(clean, edited), emptyList(), emptyList(), start)
        assertEquals(listOf("b"), plan.cards.map { it.id })
        assertEquals(null, plan.cards.single().remote)
        assertEquals(listOf(Op.Create("b")), plan.ops)
        assertEquals(1, plan.removed)
    }

    @Test
    fun unknownRemoteItemsAreImportedLinkedAndClean() {
        val plan = BoardSync.plan(emptyList(), listOf(remote("I9", "From GitHub", stamp = "s9", status = CardStatus.IN_PROGRESS)), emptyList(), start) { "new" }
        val imported = plan.cards.single()
        assertEquals("new", imported.id)
        assertEquals(CardStatus.IN_PROGRESS, imported.status)
        assertEquals(CardRemote("I9", "D_I9", "s9", start), imported.remote)
        assertTrue(imported.updatedAt <= imported.remote!!.syncedAt)
        assertEquals(1, plan.imported)
    }

    @Test
    fun tombstonesDeleteRemotelyAndAreNotReimported() {
        val plan = BoardSync.plan(emptyList(), listOf(remote("I1", "gone locally")), listOf("I1", "I_missing"), start)
        assertEquals(listOf(Op.Delete("I1")), plan.ops)
        assertTrue(plan.cards.isEmpty())
    }

    @Test
    fun mergeKeepsWorkDoneDuringSync() {
        val startIds = setOf("kept", "edited", "removedByGitHub", "deletedDuringSync")
        val link = CardRemote("I", "D", "s", start)
        val synced = listOf(
            card("kept", updatedAt = 100L, remote = link),
            card("edited", title = "sync view", remote = link.copy(itemId = "I_e")),
            card("deletedDuringSync", remote = link.copy(itemId = "I_d")),
            card("imported", remote = link.copy(itemId = "I_new")),
        )
        val current = listOf(
            card("kept", updatedAt = 100L),
            card("edited", title = "user edit", updatedAt = start + 5),
            card("removedByGitHub", updatedAt = 100L),
            card("addedDuringSync", updatedAt = start + 7),
        )
        val (cards, tombstones) = BoardSync.merge(current, listOf("I_old", "I_pending"), synced, listOf("I_old"), startIds, start)
        val byId = cards.associateBy { it.id }
        assertEquals(listOf("kept", "edited", "addedDuringSync", "imported"), cards.map { it.id })
        assertEquals(link, byId.getValue("kept").remote)
        assertEquals("user edit", byId.getValue("edited").title)
        assertEquals("I_e", byId.getValue("edited").remote?.itemId)
        assertEquals(listOf("I_pending", "I_d"), tombstones)
    }

    @Test
    fun reportSummary() {
        assertEquals("Synced with P · up to date", BoardSync.Report().summary("P"))
        assertEquals(
            "Synced with P · ↓ 3 ↑ 2 ✕ 1\nAdd Status options on GitHub for: Back burner",
            BoardSync.Report(pushed = 1, created = 1, pulled = 1, imported = 2, deleted = 1, warnings = listOf("Add Status options on GitHub for: Back burner")).summary("P"),
        )
    }
}
