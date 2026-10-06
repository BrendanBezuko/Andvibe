package com.example.andvibe

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object ChatStore {
    data class Chat(
        val id: String,
        val title: String,
        val updated: Long,
        val turns: List<Pair<String, String>>,
        val memory: List<Pair<String, String>>,
    )

    data class ChatContent(
        val chatId: String,
        val turns: List<Pair<String, String>>,
        val memory: List<Pair<String, String>>,
    )

    private const val MAX_CHATS = 50
    private const val MAX_TURN_CHARS = 20_000
    private lateinit var dir: File
    private var workspaceId = ""
    private var chatId = ""
    private val items = mutableListOf<Chat>()

    fun init(context: android.content.Context) {
        if (::dir.isInitialized) return
        dir = File(context.applicationContext.filesDir, "chats").apply { mkdirs() }
    }

    fun chats(): List<Chat> = items.sortedByDescending { it.updated }

    fun activeId(): String = chatId

    fun activeContent(): ChatContent = contentForActive()

    /** Switches persisted workspace; returns loaded chat when workspace changed. */
    fun syncWorkspace(
        turns: List<Pair<String, String>>,
        memory: List<Pair<String, String>>,
    ): ChatContent? {
        if (!::dir.isInitialized) return null
        val ws = WorkspaceStore.current().id
        if (ws == workspaceId) return null
        save(turns, memory)
        workspaceId = ws
        load()
        return contentForActive()
    }

    fun save(turns: List<Pair<String, String>>, memory: List<Pair<String, String>>) {
        if (!::dir.isInitialized || workspaceId.isEmpty()) return
        val clipped = turns.map { (role, text) -> role to text.take(MAX_TURN_CHARS) }
        if (clipped.isEmpty()) return
        if (chatId.isEmpty()) chatId = UUID.randomUUID().toString()
        val title = clipped.firstOrNull { it.first == "user" }?.second
            ?.replace(Regex("\\s+"), " ")?.trim()?.take(80)
            ?.ifBlank { null } ?: "Chat"
        val chat = Chat(chatId, title, System.currentTimeMillis(), clipped, memory.toList())
        val index = items.indexOfFirst { it.id == chatId }
        if (index >= 0) items[index] = chat else items.add(chat)
        while (items.size > MAX_CHATS) items.remove(items.minBy { it.updated })
        write()
    }

    fun openChat(
        id: String,
        currentTurns: List<Pair<String, String>>,
        currentMemory: List<Pair<String, String>>,
    ): ChatContent? {
        if (id == chatId) return null
        val chat = items.firstOrNull { it.id == id } ?: return null
        save(currentTurns, currentMemory)
        setActive(chat)
        write()
        return ChatContent(chat.id, chat.turns, chat.memory)
    }

    fun startNewChat(
        currentTurns: List<Pair<String, String>>,
        currentMemory: List<Pair<String, String>>,
    ): ChatContent {
        save(currentTurns, currentMemory)
        setActive(null)
        write()
        return contentForActive()
    }

    fun deleteChat(id: String): ChatContent? {
        items.removeAll { it.id == id }
        if (id == chatId) setActive(null)
        write()
        return contentForActive()
    }

    fun forgetWorkspace(workspace: String, agentBusy: Boolean) {
        if (!::dir.isInitialized) return
        file(workspace).delete()
        if (workspace != workspaceId) return
        workspaceId = ""
        items.clear()
        if (!agentBusy) {
            chatId = ""
        }
    }

    private fun contentForActive(): ChatContent {
        if (chatId.isEmpty()) return ChatContent("", emptyList(), emptyList())
        val chat = items.firstOrNull { it.id == chatId }
        return if (chat != null) {
            ChatContent(chat.id, chat.turns, chat.memory)
        } else {
            ChatContent("", emptyList(), emptyList())
        }
    }

    private fun setActive(chat: Chat?) {
        chatId = chat?.id.orEmpty()
    }

    private fun file(workspace: String) = File(dir, "$workspace.json")

    private fun load() {
        items.clear()
        chatId = ""
        val f = file(workspaceId)
        val text = if (f.isFile) runCatching { f.readText() }.getOrNull() else null
        if (text.isNullOrBlank()) return
        runCatching {
            val json = JSONObject(text)
            chatId = json.optString("current")
            val arr = json.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isBlank()) continue
                val turns = readPairs(obj.optJSONArray("turns"))
                if (turns.isEmpty()) continue
                items.add(
                    Chat(
                        id = id,
                        title = obj.optString("title").ifBlank { "Chat" },
                        updated = obj.optLong("updated"),
                        turns = turns,
                        memory = readPairs(obj.optJSONArray("memory")),
                    ),
                )
            }
        }
        if (items.none { it.id == chatId }) chatId = ""
    }

    private fun readPairs(arr: JSONArray?): List<Pair<String, String>> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val role = obj.optString("role")
                if (role.isBlank()) continue
                add(role to obj.optString("text"))
            }
        }
    }

    private fun writePairs(pairs: List<Pair<String, String>>): JSONArray {
        val arr = JSONArray()
        for ((role, text) in pairs) arr.put(JSONObject().put("role", role).put("text", text))
        return arr
    }

    private fun write() {
        if (!::dir.isInitialized || workspaceId.isEmpty()) return
        try {
            val arr = JSONArray()
            for (chat in items) {
                arr.put(
                    JSONObject()
                        .put("id", chat.id)
                        .put("title", chat.title)
                        .put("updated", chat.updated)
                        .put("turns", writePairs(chat.turns))
                        .put("memory", writePairs(chat.memory)),
                )
            }
            val json = JSONObject().put("current", chatId).put("items", arr)
            val target = file(workspaceId)
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(json.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            DebugLog.step("chat", "save failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }
}
