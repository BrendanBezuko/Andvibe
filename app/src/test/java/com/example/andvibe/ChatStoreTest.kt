package com.example.andvibe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Plain JVM: ChatStore is a class over a directory now, no Context or singleton reset. */
class ChatStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var workspace = "ws1"
    private var now = 1_000L

    private fun store() = ChatStore(tmp.root, { workspace }, { now++ })

    @Test
    fun savesAndReloadsPerWorkspace() {
        val a = store()
        a.syncWorkspace(emptyList(), emptyList())
        a.save(listOf("user" to "add login", "assistant" to "done"), listOf("user" to "m"))
        val chatId = a.activeId()
        assertEquals("add login", a.chats().single().title)

        val b = store()
        val loaded = b.syncWorkspace(emptyList(), emptyList())!!
        assertEquals(chatId, loaded.chatId)
        assertEquals(listOf("user" to "add login", "assistant" to "done"), loaded.turns)
        assertEquals(listOf("user" to "m"), loaded.memory)

        workspace = "ws2"
        val other = b.syncWorkspace(loaded.turns, loaded.memory)!!
        assertTrue(other.turns.isEmpty())
        assertTrue(b.chats().isEmpty())
    }

    @Test
    fun newOpenAndDeleteChats() {
        val s = store()
        s.syncWorkspace(emptyList(), emptyList())
        val first = listOf("user" to "one")
        s.save(first, emptyList())
        val firstId = s.activeId()
        assertTrue(s.startNewChat(first, emptyList()).turns.isEmpty())
        s.save(listOf("user" to "two"), emptyList())
        assertEquals(2, s.chats().size)

        val reopened = s.openChat(firstId, listOf("user" to "two"), emptyList())!!
        assertEquals(first, reopened.turns)
        assertNull("already active", s.openChat(firstId, first, emptyList()))

        s.deleteChat(firstId)
        assertEquals(listOf("two"), s.chats().map { it.title })
        assertEquals("", s.activeId())
    }

    @Test
    fun forgetWorkspaceDeletesItsFile() {
        val s = store()
        s.syncWorkspace(emptyList(), emptyList())
        s.save(listOf("user" to "x"), emptyList())
        s.forgetWorkspace("ws1", agentBusy = false)
        assertTrue(s.chats().isEmpty())
        assertTrue(tmp.root.listFiles()!!.none { it.name == "ws1.json" })
    }
}
