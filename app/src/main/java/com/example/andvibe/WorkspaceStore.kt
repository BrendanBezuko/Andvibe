package com.example.andvibe

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

object WorkspaceStore {
    enum class Column(val id: String, val label: String) {
        IDEA("idea", "Ideas"),
        BUG("bug", "Bugs"),
        SOLUTION("solution", "Solutions");

        companion object {
            fun from(id: String): Column = entries.firstOrNull { it.id == id } ?: IDEA
        }
    }

    data class Card(val id: String, val column: String, val title: String, val body: String)

    data class Workspace(
        val id: String,
        val name: String,
        val repos: Set<String>,
        val inputTokens: Long,
        val outputTokens: Long,
        val costMicros: Long,
        val cards: List<Card>
    )

    private const val MAX_CARDS = 200
    private lateinit var file: File
    private var ready = false
    private var currentId = ""
    private var scoped = false
    private val items = mutableListOf<Workspace>()

    fun init(context: android.content.Context) {
        synchronized(this) {
            if (ready) return
            file = File(context.applicationContext.filesDir, "workspaces.json")
            load()
            ready = true
        }
    }

    fun current(): Workspace = synchronized(this) { live() }

    fun workspaces(): List<Workspace> = synchronized(this) { items.toList() }

    fun cards(column: Column): List<Card> = synchronized(this) {
        live().cards.filter { it.column == column.id }
    }

    fun downloaded(): List<String> {
        return AppState.reposDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.mapNotNull { cleanRepo(it.name) }
            ?.sortedBy { it.lowercase() }
            .orEmpty()
    }

    fun activeRepos(): List<File> {
        val names = current().repos
        return downloaded().filter { it in names }.map { File(AppState.reposDir, it) }
    }

    fun contains(file: File): Boolean {
        val root = AppState.reposDir.canonicalFile
        val canon = file.canonicalFile
        if (canon == root) return true
        if (!canon.path.startsWith(root.path + File.separator)) return false
        return RepoFiles.rel(canon, root).substringBefore('/') in current().repos
    }

    fun include(name: String) = setRepo(name, true)

    fun migrate() {
        synchronized(this) {
            if (scoped) return
            scoped = true
            if (items.all { it.repos.isEmpty() }) {
                val names = downloaded().toSet()
                val index = items.indexOfFirst { it.id == currentId }
                if (index >= 0 && names.isNotEmpty()) items[index] = items[index].copy(repos = names)
            }
            save()
        }
    }

    fun rename(name: String) {
        val clean = cleanName(name)
        update { it.copy(name = clean) }
    }

    fun setRepo(name: String, included: Boolean) {
        val clean = cleanRepo(name) ?: return
        update { ws ->
            val next = ws.repos.toMutableSet()
            if (included) next.add(clean) else next.remove(clean)
            ws.copy(repos = next)
        }
    }

    fun select(id: String): Boolean = synchronized(this) {
        if (items.none { it.id == id } || currentId == id) return false
        currentId = id
        save()
        true
    }

    fun create(name: String): String = synchronized(this) {
        val id = UUID.randomUUID().toString()
        items.add(Workspace(id, cleanName(name), emptySet(), 0, 0, 0, emptyList()))
        currentId = id
        save()
        id
    }

    fun delete(id: String): Boolean = synchronized(this) {
        if (items.size <= 1) return false
        if (items.none { it.id == id }) return false
        items.removeAll { it.id == id }
        if (currentId == id || items.none { it.id == currentId }) currentId = items.first().id
        save()
        true
    }

    fun addCard(column: String, title: String, body: String): Boolean {
        val cleanTitle = title.trim().take(200)
        if (cleanTitle.isEmpty()) return false
        var added = false
        update { ws ->
            if (ws.cards.size >= MAX_CARDS) return@update ws
            added = true
            val card = Card(
                UUID.randomUUID().toString(),
                Column.from(column).id,
                cleanTitle,
                body.trim().take(4000)
            )
            ws.copy(cards = ws.cards + card)
        }
        return added
    }

    fun updateCard(id: String, title: String, body: String) {
        val cleanTitle = title.trim().take(200)
        if (cleanTitle.isEmpty()) return
        update { ws ->
            ws.copy(
                cards = ws.cards.map { card ->
                    if (card.id == id) card.copy(title = cleanTitle, body = body.trim().take(4000)) else card
                }
            )
        }
    }

    fun moveCard(id: String, column: String) {
        val next = Column.from(column).id
        update { ws ->
            ws.copy(cards = ws.cards.map { if (it.id == id) it.copy(column = next) else it })
        }
    }

    fun deleteCard(id: String) {
        update { ws -> ws.copy(cards = ws.cards.filterNot { it.id == id }) }
    }

    fun addUse(model: String, input: Long, output: Long) {
        val inTokens = input.coerceAtLeast(0)
        val outTokens = output.coerceAtLeast(0)
        if (inTokens == 0L && outTokens == 0L) return
        synchronized(this) {
            if (!ready) return
            val extra = estimateMicros(model, inTokens, outTokens)
            update { ws ->
                ws.copy(
                    inputTokens = ws.inputTokens + inTokens,
                    outputTokens = ws.outputTokens + outTokens,
                    costMicros = ws.costMicros + extra
                )
            }
        }
        UiBridge.usageUpdate()
    }

    fun priceText(micros: Long): String {
        val usd = micros / 1_000_000.0
        return when {
            usd >= 100 -> "$" + usd.roundToLong().toString()
            usd >= 1 -> String.format(Locale.US, "$%.2f", usd)
            else -> String.format(Locale.US, "$%.4f", usd)
        }
    }

    private fun live(): Workspace {
        if (items.isEmpty()) {
            val id = UUID.randomUUID().toString()
            items.add(Workspace(id, "Workspace", emptySet(), 0, 0, 0, emptyList()))
            currentId = id
        }
        return items.firstOrNull { it.id == currentId } ?: items.first().also { currentId = it.id }
    }

    private fun update(block: (Workspace) -> Workspace) {
        synchronized(this) {
            if (!ready && !::file.isInitialized) return
            val index = items.indexOfFirst { it.id == currentId }
            if (index < 0) return
            items[index] = block(items[index])
            save()
        }
    }

    private fun cleanName(name: String): String {
        val clean = name.trim().replace(Regex("\\s+"), " ").take(40)
        return clean.ifBlank { "Workspace" }
    }

    private fun cleanRepo(name: String): String? {
        val clean = name.trim()
        if (clean.isEmpty() || clean.contains('/') || clean.contains('\\') || clean == "." || clean == "..") {
            return null
        }
        return clean
    }

    private fun estimateMicros(model: String, input: Long, output: Long): Long {
        val (inPerM, outPerM) = rates(model)
        val usd = input * inPerM / 1_000_000.0 + output * outPerM / 1_000_000.0
        return (usd * 1_000_000.0).roundToLong().coerceAtLeast(0)
    }

    private fun rates(model: String): Pair<Double, Double> {
        val name = model.lowercase(Locale.US)
        return when {
            "opus" in name || name.startsWith("o1") || name.startsWith("o3") -> 15.0 to 75.0
            "mini" in name || "flash" in name || "haiku" in name -> 0.15 to 0.60
            "sonnet" in name || "claude" in name -> 3.0 to 15.0
            "grok" in name -> 3.0 to 15.0
            "gemini" in name -> 1.25 to 5.0
            "gpt" in name -> 2.5 to 10.0
            else -> 3.0 to 15.0
        }
    }

    private fun load() {
        items.clear()
        val text = if (file.isFile) runCatching { file.readText() }.getOrNull() else null
        if (!text.isNullOrBlank()) {
            runCatching {
                val json = JSONObject(text)
                currentId = json.optString("current")
                scoped = json.optBoolean("scoped", false)
                val arr = json.optJSONArray("items") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    if (id.isBlank()) continue
                    items.add(
                        Workspace(
                            id = id,
                            name = cleanName(obj.optString("name")),
                            repos = readRepos(obj.optJSONArray("repos")),
                            inputTokens = obj.optLong("input").coerceAtLeast(0),
                            outputTokens = obj.optLong("output").coerceAtLeast(0),
                            costMicros = obj.optLong("micros").coerceAtLeast(0),
                            cards = readCards(obj.optJSONArray("cards"))
                        )
                    )
                }
            }
        }
        if (items.isEmpty()) {
            val id = UUID.randomUUID().toString()
            items.add(Workspace(id, "Workspace", emptySet(), 0, 0, 0, emptyList()))
            currentId = id
            save()
        } else if (items.none { it.id == currentId }) {
            currentId = items.first().id
            save()
        }
    }

    private fun readRepos(arr: JSONArray?): Set<String> {
        if (arr == null) return emptySet()
        return buildSet {
            for (i in 0 until arr.length()) {
                cleanRepo(arr.optString(i))?.let { add(it) }
            }
        }
    }

    private fun readCards(arr: JSONArray?): List<Card> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                if (size >= MAX_CARDS) break
                val obj = arr.optJSONObject(i) ?: continue
                val title = obj.optString("title").trim().take(200)
                if (title.isEmpty()) continue
                val id = obj.optString("id").ifBlank { UUID.randomUUID().toString() }
                add(
                    Card(
                        id = id,
                        column = Column.from(obj.optString("column")).id,
                        title = title,
                        body = obj.optString("body").trim().take(4000)
                    )
                )
            }
        }
    }

    private fun save() {
        if (!::file.isInitialized) return
        try {
            val arr = JSONArray()
            for (ws in items) {
                val repos = JSONArray()
                ws.repos.sorted().forEach { repos.put(it) }
                val cards = JSONArray()
                for (card in ws.cards) {
                    cards.put(
                        JSONObject()
                            .put("id", card.id)
                            .put("column", card.column)
                            .put("title", card.title)
                            .put("body", card.body)
                    )
                }
                arr.put(
                    JSONObject()
                        .put("id", ws.id)
                        .put("name", ws.name)
                        .put("repos", repos)
                        .put("input", ws.inputTokens)
                        .put("output", ws.outputTokens)
                        .put("micros", ws.costMicros)
                        .put("cards", cards)
                )
            }
            val json = JSONObject().put("current", currentId).put("scoped", scoped).put("items", arr)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(json.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            DebugLog.step("workspace", "save failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }
}
