package com.example.andvibe.features.board

import com.example.andvibe.CardRemote
import com.example.andvibe.CardStatus
import com.example.andvibe.CardType
import com.example.andvibe.ProjectLink
import com.example.andvibe.WorkspaceStore.Card
import com.example.andvibe.github.GitHubProjects
import java.time.Instant
import java.util.UUID

/**
 * Two-way Board ⇄ GitHub Project sync.
 *
 * A linked card is *locally changed* when updatedAt > remote.syncedAt, and *remotely changed*
 * when the item's stamp differs from the one recorded at the last sync. If both changed, the
 * newer edit wins. [plan] is pure; [Runner] talks to GitHub.
 */
object BoardSync {
    /** A project item reduced to Board terms. Null status/type = no (mappable) value on GitHub. */
    data class Remote(
        val itemId: String,
        val contentId: String,
        val isIssue: Boolean,
        val title: String,
        val body: String,
        val status: CardStatus?,
        val type: CardType?,
        val stamp: String,
        val updatedAt: Long,
        val issue: com.example.andvibe.CardIssue?,
    )

    sealed interface Op {
        val cardId: String?

        data class Create(override val cardId: String) : Op
        data class PushContent(override val cardId: String) : Op
        data class PushStatus(override val cardId: String) : Op
        data class PushType(override val cardId: String) : Op
        data class Delete(val itemId: String) : Op {
            override val cardId: String? get() = null
        }
    }

    data class Plan(
        /** Local board after pulls/imports/removals, before remote ops run. */
        val cards: List<Card>,
        val ops: List<Op>,
        val pulled: Int,
        val imported: Int,
        val removed: Int,
    )

    fun plan(
        cards: List<Card>,
        remotes: List<Remote>,
        deleted: List<String>,
        syncStart: Long,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): Plan {
        val byItem = remotes.associateBy { it.itemId }
        val tombstones = deleted.toSet()
        val ops = mutableListOf<Op>()
        val out = mutableListOf<Card>()
        var pulled = 0
        var removed = 0

        for (itemId in deleted) if (itemId in byItem) ops.add(Op.Delete(itemId))

        for (card in cards) {
            val link = card.remote
            if (link == null) {
                out.add(card)
                ops.add(Op.Create(card.id))
                continue
            }
            val localChanged = card.updatedAt > link.syncedAt
            val remote = byItem[link.itemId]
            if (remote == null) {
                // Deleted or archived on GitHub. Keep local edits by re-creating; otherwise follow GitHub.
                if (localChanged) {
                    out.add(card.copy(remote = null))
                    ops.add(Op.Create(card.id))
                } else {
                    removed++
                }
                continue
            }
            val remoteChanged = remote.stamp != link.stamp
            val pull = remoteChanged && (!localChanged || remote.updatedAt > card.updatedAt)
            if (pull) {
                out.add(pullInto(card, remote, syncStart))
                pulled++
            } else {
                out.add(card)
                if (localChanged) {
                    if (card.title != remote.title || card.body != remote.body) ops.add(Op.PushContent(card.id))
                    if (card.status != remote.status) ops.add(Op.PushStatus(card.id))
                    if (card.type != remote.type) ops.add(Op.PushType(card.id))
                }
            }
        }

        val linked = cards.mapNotNull { it.remote?.itemId }.toSet()
        var imported = 0
        for (remote in remotes) {
            if (remote.itemId in linked || remote.itemId in tombstones) continue
            out.add(
                Card(
                    id = newId(),
                    type = remote.type ?: CardType.FEATURE,
                    status = remote.status ?: CardStatus.TODO,
                    title = remote.title.ifBlank { "(untitled)" }.take(200),
                    body = remote.body.take(4000),
                    updatedAt = syncStart,
                    issue = remote.issue,
                    remote = CardRemote(remote.itemId, remote.contentId, remote.stamp, syncStart),
                )
            )
            imported++
        }
        return Plan(out, ops, pulled, imported, removed)
    }

    private fun pullInto(card: Card, remote: Remote, syncStart: Long) = card.copy(
        title = remote.title.ifBlank { card.title }.take(200),
        body = remote.body.take(4000),
        status = remote.status ?: card.status,
        type = remote.type ?: card.type,
        issue = remote.issue ?: card.issue,
        updatedAt = syncStart,
    )

    /**
     * Folds a finished sync into the board as it is *now*: edits, adds, and deletes made while
     * the sync ran win over the sync's view. Returns (cards, new tombstones).
     */
    fun merge(
        current: List<Card>,
        currentDeleted: List<String>,
        synced: List<Card>,
        processedDeletes: Collection<String>,
        startIds: Set<String>,
        syncStart: Long,
    ): Pair<List<Card>, List<String>> {
        val syncedById = synced.associateBy { it.id }
        val currentIds = current.map { it.id }.toSet()
        val cards = mutableListOf<Card>()
        for (card in current) {
            val s = syncedById[card.id]
            cards += when {
                s == null && card.id in startIds && card.updatedAt <= syncStart -> continue // removed by GitHub
                s == null -> card // added during sync, or edited during sync after GitHub removed it
                card.updatedAt > syncStart -> card.copy(remote = s.remote) // edited during sync: keep edit, take link
                else -> s
            }
        }
        val tombstones = (currentDeleted - processedDeletes.toSet()).toMutableList()
        for (s in synced) {
            if (s.id in currentIds) continue
            if (s.id in startIds) {
                // Deleted locally during sync; if sync linked it, remove it on GitHub next time.
                s.remote?.itemId?.let { if (it !in tombstones) tombstones += it }
            } else {
                cards += s // imported from GitHub
            }
        }
        return cards to tombstones
    }

    data class Report(
        val pushed: Int = 0,
        val created: Int = 0,
        val pulled: Int = 0,
        val imported: Int = 0,
        val removed: Int = 0,
        val deleted: Int = 0,
        val warnings: List<String> = emptyList(),
    ) {
        fun summary(projectTitle: String): String {
            val parts = buildList {
                if (pulled + imported > 0) add("↓ ${pulled + imported}")
                if (pushed + created > 0) add("↑ ${pushed + created}")
                if (removed + deleted > 0) add("✕ ${removed + deleted}")
            }
            val head = "Synced with $projectTitle" + if (parts.isEmpty()) " · up to date" else " · " + parts.joinToString(" ")
            return if (warnings.isEmpty()) head else head + "\n" + warnings.joinToString("\n")
        }
    }

    data class Outcome(
        val cards: List<Card>,
        val processedDeletes: List<String>,
        val projectTitle: String,
        val report: Report,
    )

    class Runner(
        private val gh: GitHubProjects,
        private val newId: () -> String = { UUID.randomUUID().toString() },
    ) {
        fun run(link: ProjectLink, cards: List<Card>, deleted: List<String>, syncStart: Long): Outcome {
            val project = gh.ensureTypeField(gh.project(link))
            val warnings = mutableListOf<String>()
            val statusField = project.status
            if (statusField == null) {
                warnings += "Project has no Status field; statuses were not synced."
            } else {
                val missing = CardStatus.entries.filter { statusOption(project, it) == null }
                if (missing.isNotEmpty()) {
                    warnings += "Add Status options on GitHub for: " + missing.joinToString { it.label }
                }
            }

            val remotes = gh.items(project).map { toRemote(it, project) }
            val plan = plan(cards, remotes, deleted, syncStart, newId)
            val working = plan.cards.associateBy { it.id }.toMutableMap()
            val remoteById = remotes.associateBy { it.itemId }
            var pushed = 0
            var created = 0
            var deletedCount = 0
            val processed = mutableListOf<String>()
            val failures = LinkedHashSet<String>()

            for (op in plan.ops) {
                try {
                    when (op) {
                        is Op.Delete -> {
                            gh.deleteItem(project.id, op.itemId)
                            deletedCount++
                            processed += op.itemId
                        }
                        is Op.Create -> {
                            val card = working.getValue(op.cardId)
                            val issue = card.issue
                            val (itemId, contentId) = if (issue != null && issue.nodeId.isNotBlank()) {
                                gh.addContent(project.id, issue.nodeId) to issue.nodeId
                            } else {
                                gh.createDraft(project.id, card.title, card.body)
                            }
                            working[card.id] = card.copy(remote = CardRemote(itemId, contentId, "", syncStart))
                            setStatus(project, itemId, card.status)
                            setType(project, itemId, card.type)
                            created++
                        }
                        is Op.PushContent -> {
                            val card = working.getValue(op.cardId)
                            val link = card.remote ?: continue
                            // Use GitHub's current content id: a draft converted to an issue gets a new one.
                            val remote = remoteById[link.itemId]
                            val contentId = remote?.contentId ?: link.contentId
                            if (remote?.isIssue == true) {
                                gh.updateIssue(contentId, card.title, card.body)
                            } else {
                                gh.updateDraft(contentId, card.title, card.body)
                            }
                            pushed++
                        }
                        is Op.PushStatus -> {
                            val card = working.getValue(op.cardId)
                            if (setStatus(project, card.remote?.itemId ?: continue, card.status)) pushed++
                        }
                        is Op.PushType -> {
                            val card = working.getValue(op.cardId)
                            if (setType(project, card.remote?.itemId ?: continue, card.type)) pushed++
                        }
                    }
                } catch (t: Throwable) {
                    failures += (t.message ?: t.javaClass.simpleName)
                }
            }
            // Ops that already happened (deletes) count as processed even when a later op failed.
            warnings += failures.take(3)

            // Re-read so every linked card records GitHub's post-sync stamp.
            val after = gh.items(project).associateBy { it.itemId }
            val stamped = plan.cards.map { original ->
                val card = working[original.id] ?: original
                val link = card.remote ?: return@map card
                val item = after[link.itemId] ?: return@map card
                card.copy(remote = link.copy(contentId = item.contentId, stamp = item.stamp, syncedAt = syncStart))
            }
            return Outcome(
                cards = stamped,
                processedDeletes = processed + deleted.filter { it !in remoteById },
                projectTitle = project.title,
                report = Report(
                    pushed = pushed,
                    created = created,
                    pulled = plan.pulled,
                    imported = plan.imported,
                    removed = plan.removed,
                    deleted = deletedCount,
                    warnings = warnings,
                ),
            )
        }

        private fun setStatus(project: GitHubProjects.Project, itemId: String, status: CardStatus): Boolean {
            val field = project.status ?: return false
            val option = statusOption(project, status) ?: return false
            gh.setOption(project.id, itemId, field.id, option)
            return true
        }

        private fun setType(project: GitHubProjects.Project, itemId: String, type: CardType): Boolean {
            val field = project.type ?: return false
            val option = field.options.firstOrNull { CardType.parse(it.name) == type }?.id ?: return false
            gh.setOption(project.id, itemId, field.id, option)
            return true
        }
    }

    internal fun statusOption(project: GitHubProjects.Project, status: CardStatus): String? =
        project.status?.options?.firstOrNull { CardStatus.parse(it.name) == status }?.id

    internal fun toRemote(item: GitHubProjects.Item, project: GitHubProjects.Project): Remote {
        val statusName = project.status?.let { f -> f.options.firstOrNull { it.id == item.options[f.id] }?.name }
        val typeName = project.type?.let { f -> f.options.firstOrNull { it.id == item.options[f.id] }?.name }
        val status = CardStatus.parse(statusName) ?: if (item.closed) CardStatus.DONE else null
        return Remote(
            itemId = item.itemId,
            contentId = item.contentId,
            isIssue = item.isIssue,
            title = item.title,
            body = item.body,
            status = status,
            type = CardType.parse(typeName),
            stamp = item.stamp,
            updatedAt = maxOf(millis(item.itemUpdatedAt), millis(item.contentUpdatedAt)),
            issue = item.issue,
        )
    }

    private fun millis(iso: String): Long = runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)
}
