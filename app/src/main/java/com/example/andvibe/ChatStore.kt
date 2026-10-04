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
        val memory: List<Pair<String, String>>
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
        sync()
    }

    fun chats(): List<Chat> = items.sortedByDescending { it.updated }

    fun activeId(): String = chatId

    fun sync(): Boolean {
        if (!::dir.isInitialized || AppState.vibeBusy) return false
        val ws = WorkspaceStore.current().id
        if (ws == workspaceId) return false
        save()
        workspaceId = ws
        load()
        show(items.firstOrNull { it.id == chatId })
        return true
    }

    fun save() {
        if (!::dir.isInitialized || workspaceId.isEmpty()) return
        val turns = AppState.chat.map { (role, text) -> role to text.take(MAX_TURN_CHARS) }
        if (turns.isEmpty()) return
        if (chatId.isEmpty()) chatId = UUID.randomUUID().toString()
        val title = turns.firstOrNull { it.first == "user" }?.second
            ?.replace(Regex("\\s+"), " ")?.trim()?.take(80)
            ?.ifBlank { null } ?: "Chat"
        val chat = Chat(chatId, title, System.currentTimeMillis(), turns, AppState.history.toList())
        val index = items.indexOfFirst { it.id == chatId }
        if (index >= 0) items[index] = chat else items.add(chat)
        while (items.size > MAX_CHATS) items.remove(items.minBy { it.updated })
        write()
    }

    fun open(id: String): Boolean {
        if (AppState.vibeBusy || id == chatId) return false
        val chat = items.firstOrNull { it.id == id } ?: return false
        save()
        show(chat)
        write()
        return true
    }

    fun startNew() {
        if (AppState.vibeBusy) return
        save()
        show(null)
        write()
    }

    fun delete(id: String) {
        if (AppState.vibeBusy && id == chatId) return
        items.removeAll { it.id == id }
        if (id == chatId) show(null)
        write()
    }

    fun forget(workspace: String) {
        if (!::dir.isInitialized) return
        file(workspace).delete()
        if (workspace != workspaceId) return
        workspaceId = ""
        items.clear()
        if (!AppState.vibeBusy) show(null)
    }

    private fun show(chat: Chat?) {
        chatId = chat?.id.orEmpty()
        AppState.chat.clear()
        AppState.history.clear()
        if (chat == null) return
        AppState.chat.addAll(chat.turns)
        chat.memory.forEach { AppState.history.addLast(it) }
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
                        memory = readPairs(obj.optJSONArray("memory"))
                    )
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
                        .put("memory", writePairs(chat.memory))
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
