package com.example.andvibe

import com.example.andvibe.core.RepoFiles

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

object WorkspaceStore {
    data class Card(
        val id: String,
        val type: CardType,
        val status: CardStatus,
        val title: String,
        val body: String,
        /** Local clock of the last local edit; sync compares it to [CardRemote.syncedAt]. */
        val updatedAt: Long = 0L,
        val issue: CardIssue? = null,
        val remote: CardRemote? = null,
    )

    data class Workspace(
        val id: String,
        val name: String,
        val repos: Set<String>,
        val inputTokens: Long,
        val outputTokens: Long,
        val costMicros: Long,
        val cards: List<Card>,
        val project: ProjectLink? = null,
        /** Project item ids of synced cards deleted locally; the next sync removes them on GitHub. */
        val deletedRemote: List<String> = emptyList(),
    )

    private const val MAX_CARDS = 200
    private lateinit var file: File
    private lateinit var reposDir: File
    private var ready = false
    private var currentId = ""
    private var scoped = false
    private val items = mutableListOf<Workspace>()

    @Volatile
    var onUsageChanged: (() -> Unit)? = null

    /** Card edit clock; tests pin it. */
    @Volatile
    internal var clock: () -> Long = System::currentTimeMillis

    fun init(context: android.content.Context) {
        synchronized(this) {
            if (ready) return
            val app = context.applicationContext
            file = File(app.filesDir, "workspaces.json")
            reposDir = File(app.filesDir, "repos").apply { mkdirs() }
            load()
            ready = true
        }
    }

    /** Clears singleton state between JVM unit tests. */
    internal fun resetForTests() {
        synchronized(this) {
            ready = false
            items.clear()
            currentId = ""
            scoped = false
            onUsageChanged = null
            clock = System::currentTimeMillis
        }
    }

    fun current(): Workspace = synchronized(this) { live() }

    fun workspaces(): List<Workspace> = synchronized(this) { items.toList() }

    fun cards(status: CardStatus): List<Card> = synchronized(this) {
        live().cards.filter { it.status == status }
    }

    fun downloaded(): List<String> {
        return reposDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.mapNotNull { cleanRepo(it.name) }
            ?.sortedBy { it.lowercase() }
            .orEmpty()
    }

    fun activeRepos(): List<File> {
        val names = current().repos
        return downloaded().filter { it in names }.map { File(reposDir, it) }
    }

    fun contains(file: File): Boolean {
        val root = reposDir.canonicalFile
        val canon = file.canonicalFile
        if (canon == root) return true
        if (!canon.path.startsWith(root.path + File.separator)) return false
        return RepoFiles.rel(canon, root).substringBefore('/') in current().repos
    }

    fun include(name: String) = setRepo(name, true)

    /**
     * Deletes a downloaded repo folder from disk and drops it from every workspace.
     * Returns false if the name is invalid or the path is outside the repos dir.
     */
    fun deleteDownloaded(name: String): Boolean = synchronized(this) {
        val clean = cleanRepo(name) ?: return false
        if (!::reposDir.isInitialized) return false
        val root = reposDir.canonicalFile
        val dir = File(reposDir, clean).canonicalFile
        if (dir == root || !dir.path.startsWith(root.path + File.separator)) return false
        if (dir.exists()) {
            if (!dir.isDirectory) return false
            if (!dir.deleteRecursively() && dir.exists()) return false
        }
        var changed = false
        for (i in items.indices) {
            if (clean in items[i].repos) {
                items[i] = items[i].copy(repos = items[i].repos - clean)
                changed = true
            }
        }
        if (changed) save()
        true
    }

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

    fun addCard(
        type: CardType,
        status: CardStatus,
        title: String,
        body: String,
    ): Boolean {
        val cleanTitle = title.trim().take(200)
        if (cleanTitle.isEmpty()) return false
        var added = false
        update { ws ->
            if (ws.cards.size >= MAX_CARDS) return@update ws
            added = true
            val card = Card(
                id = UUID.randomUUID().toString(),
                type = type,
                status = status,
                title = cleanTitle,
                body = body.trim().take(4000),
                updatedAt = clock(),
            )
            ws.copy(cards = ws.cards + card)
        }
        return added
    }

    fun updateCard(id: String, title: String, body: String, type: CardType? = null) {
        val cleanTitle = title.trim().take(200)
        if (cleanTitle.isEmpty()) return
        editCard(id) { it.copy(title = cleanTitle, body = body.trim().take(4000), type = type ?: it.type) }
    }

    fun moveCard(id: String, status: CardStatus) {
        editCard(id) { if (it.status == status) it else it.copy(status = status) }
    }

    fun setIssue(id: String, issue: CardIssue) {
        // Not a content edit: no updatedAt bump, so sync doesn't treat it as a local change.
        update { ws -> ws.copy(cards = ws.cards.map { if (it.id == id) it.copy(issue = issue) else it }) }
    }

    fun deleteCard(id: String) {
        update { ws ->
            val gone = ws.cards.firstOrNull { it.id == id } ?: return@update ws
            ws.copy(
                cards = ws.cards - gone,
                deletedRemote = gone.remote?.let { ws.deletedRemote + it.itemId } ?: ws.deletedRemote,
            )
        }
    }

    /** Links (or with null, unlinks) the current workspace's board to a GitHub Project. */
    fun setProject(link: ProjectLink?) {
        update { ws ->
            if (ws.project == link) return@update ws
            // A different project means old item ids are meaningless: drop links and tombstones.
            ws.copy(
                project = link,
                cards = ws.cards.map { it.copy(remote = null) },
                deletedRemote = emptyList(),
            )
        }
    }

    /** Applies [block] to workspace [id] (not necessarily current). False if it no longer exists. */
    fun updateWorkspace(id: String, block: (Workspace) -> Workspace): Boolean = synchronized(this) {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        items[index] = block(items[index])
        save()
        true
    }

    fun workspace(id: String): Workspace? = synchronized(this) { items.firstOrNull { it.id == id } }

    private fun editCard(id: String, block: (Card) -> Card) {
        update { ws ->
            ws.copy(
                cards = ws.cards.map { card ->
                    if (card.id != id) return@map card
                    val next = block(card)
                    if (next == card) card else next.copy(updatedAt = clock())
                }
            )
        }
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
        onUsageChanged?.invoke()
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
                            cards = readCards(obj.optJSONArray("cards")),
                            project = obj.optJSONObject("project")?.let { readProject(it) },
                            deletedRemote = readStrings(obj.optJSONArray("deleted_remote")),
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
                val (type, status) = if (obj.has("status")) {
                    CardType.from(obj.optString("type")) to CardStatus.from(obj.optString("status"))
                } else {
                    legacyColumn(obj.optString("column"))
                }
                add(
                    Card(
                        id = id,
                        type = type,
                        status = status,
                        title = title,
                        body = obj.optString("body").trim().take(4000),
                        updatedAt = obj.optLong("updated", 0L),
                        issue = obj.optJSONObject("issue")?.let { readIssue(it) },
                        remote = obj.optJSONObject("remote")?.let { readRemote(it) },
                    )
                )
            }
        }
    }

    /** Pre-status boards had one `column` mixing type and status. */
    internal fun legacyColumn(column: String): Pair<CardType, CardStatus> = when (column) {
        "bug" -> CardType.BUG to CardStatus.TODO
        "solution" -> CardType.SOLUTION to CardStatus.TODO
        "completed" -> CardType.FEATURE to CardStatus.DONE
        else -> CardType.FEATURE to CardStatus.TODO
    }

    private fun readIssue(obj: JSONObject): CardIssue? {
        val number = obj.optInt("number", 0)
        val url = obj.optString("url")
        if (number <= 0 || url.isBlank()) return null
        return CardIssue(number, url, obj.optString("node"))
    }

    private fun readRemote(obj: JSONObject): CardRemote? {
        val item = obj.optString("item")
        if (item.isBlank()) return null
        return CardRemote(item, obj.optString("content"), obj.optString("stamp"), obj.optLong("synced", 0L))
    }

    private fun readProject(obj: JSONObject): ProjectLink? {
        val owner = obj.optString("owner")
        val number = obj.optInt("number", 0)
        if (owner.isBlank() || number <= 0) return null
        return ProjectLink(owner, obj.optBoolean("org", false), number)
    }

    private fun readStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    }

    private fun writeCard(card: Card): JSONObject {
        val obj = JSONObject()
            .put("id", card.id)
            .put("type", card.type.id)
            .put("status", card.status.id)
            .put("title", card.title)
            .put("body", card.body)
            .put("updated", card.updatedAt)
        card.issue?.let {
            obj.put("issue", JSONObject().put("number", it.number).put("url", it.url).put("node", it.nodeId))
        }
        card.remote?.let {
            obj.put(
                "remote",
                JSONObject()
                    .put("item", it.itemId)
                    .put("content", it.contentId)
                    .put("stamp", it.stamp)
                    .put("synced", it.syncedAt),
            )
        }
        return obj
    }

    private fun save() {
        if (!::file.isInitialized) return
        try {
            val arr = JSONArray()
            for (ws in items) {
                val repos = JSONArray()
                ws.repos.sorted().forEach { repos.put(it) }
                val cards = JSONArray()
                for (card in ws.cards) cards.put(writeCard(card))
                val obj = JSONObject()
                    .put("id", ws.id)
                    .put("name", ws.name)
                    .put("repos", repos)
                    .put("input", ws.inputTokens)
                    .put("output", ws.outputTokens)
                    .put("micros", ws.costMicros)
                    .put("cards", cards)
                ws.project?.let {
                    obj.put("project", JSONObject().put("owner", it.owner).put("org", it.org).put("number", it.number))
                }
                if (ws.deletedRemote.isNotEmpty()) obj.put("deleted_remote", JSONArray(ws.deletedRemote))
                arr.put(obj)
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
