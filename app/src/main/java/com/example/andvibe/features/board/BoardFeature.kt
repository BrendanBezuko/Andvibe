package com.example.andvibe.features.board

import android.os.Handler
import android.os.Looper
import com.example.andvibe.AiClient
import com.example.andvibe.CardIssue
import com.example.andvibe.CardStatus
import com.example.andvibe.CardType
import com.example.andvibe.ProjectLink
import com.example.andvibe.ProjectSession
import com.example.andvibe.PromptStore
import com.example.andvibe.Provider
import com.example.andvibe.RequirementsStore
import com.example.andvibe.Tab
import com.example.andvibe.WorkspaceStore
import com.example.andvibe.features.FeatureEffects
import com.example.andvibe.github.GitHubProjects
import com.example.andvibe.tasks.AppDispatchers
import com.example.andvibe.tasks.Res
import com.example.andvibe.tasks.TaskRunner
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class BoardFeature(
    private val tasks: TaskRunner,
    private val dispatchers: AppDispatchers,
    private val session: ProjectSession,
    private val log: (String) -> Unit,
    private val github: GitHubProjects,
    /** owner/repo of the open project's GitHub origin, or blank. */
    private val repoSlug: () -> String,
) {
    data class Creds(
        val provider: Provider,
        val key: String,
        val model: String,
        val base: String,
        val apiStyle: com.example.andvibe.ApiStyle = com.example.andvibe.ApiStyle.from(provider),
    )

    data class State(
        val cards: List<WorkspaceStore.Card> = emptyList(),
        val agentBusy: Boolean = false,
        val listening: Boolean = false,
        val drafting: Boolean = false,
        val partial: String = "",
        val status: String = "",
        val requirements: String = "",
        val project: ProjectLink? = null,
        val syncing: Boolean = false,
    )

    sealed interface Effect {
        data class SendToVibe(val instruction: String) : Effect
        data class EditCard(val card: WorkspaceStore.Card) : Effect
        data class Toast(val message: String) : Effect
    }

    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val effects = FeatureEffects<Effect>()
    val effectsFlow = effects.events

    /** Serialized recordings so each LLM pass sees prior writes. */
    private val pendingAudio = ArrayDeque<File>()
    private var lastCreds: Creds? = null

    fun refresh() {
        val ws = WorkspaceStore.current()
        _state.update {
            it.copy(
                cards = ws.cards,
                agentBusy = tasks.holds(Res.AGENT),
                drafting = tasks.holds(Res.BOARD) && !it.syncing,
                requirements = RequirementsStore.read(ws.id),
                project = ws.project,
            )
        }
    }

    fun cardsIn(status: CardStatus): List<WorkspaceStore.Card> = WorkspaceStore.cards(status)

    fun addCard(type: CardType, status: CardStatus, title: String, body: String): Boolean {
        val added = WorkspaceStore.addCard(type, status, title, body)
        refresh()
        return added
    }

    fun moveCard(id: String, status: CardStatus) {
        WorkspaceStore.moveCard(id, status)
        refresh()
    }

    fun deleteCard(id: String) {
        WorkspaceStore.deleteCard(id)
        refresh()
    }

    fun updateCard(id: String, title: String, body: String, type: CardType) {
        WorkspaceStore.updateCard(id, title, body, type)
        refresh()
    }

    /** Sends the card to Vibe and marks it In progress (Done cards are revisited in place). */
    fun buildCard(card: WorkspaceStore.Card) {
        if (tasks.holds(Res.AGENT)) return
        if (card.status != CardStatus.DONE) moveCard(card.id, CardStatus.IN_PROGRESS)
        effects.tryEmit(Effect.SendToVibe(instructionFor(card)))
    }

    fun instructionFor(card: WorkspaceStore.Card): String = Companion.instructionFor(card)

    companion object {
        /** Result lines worth keeping on screen after recording stops. */
        private val KEEP_STATUS = listOf(
            "Added", "Moved", "Updated", "Heard", "No board", "Synced", "Sync failed", "Raised", "Raise issue", "Linked",
        )

        /** Board → Vibe prompt (lead + title + note). */
        fun instructionFor(card: WorkspaceStore.Card): String {
            val lead = when {
                card.status == CardStatus.DONE -> "Revisit this completed Board item."
                card.type == CardType.BUG -> "Fix this bug from the Board."
                card.type == CardType.SOLUTION -> "Implement this solution from the Board."
                else -> "Implement this feature from the Board."
            }
            return buildString {
                append(lead)
                append("\n\n")
                append(card.title.trim())
                val note = card.body.trim()
                if (note.isNotEmpty()) {
                    append("\n\n")
                    append(note)
                }
                card.issue?.let { append("\n\nGitHub issue #${it.number}: ${it.url}") }
                append("\n\nMake a focused change in the open repo. Do not commit.")
            }
        }
    }

    /** Links the current workspace's board to a GitHub Project URL. False if the URL isn't one. */
    fun linkProject(url: String): Boolean {
        val link = ProjectLink.parse(url) ?: return false
        WorkspaceStore.setProject(link)
        _state.update { it.copy(status = "Linked ${link.url} — tap Sync") }
        refresh()
        return true
    }

    fun unlinkProject() {
        WorkspaceStore.setProject(null)
        _state.update { it.copy(status = "Unlinked GitHub Project") }
        refresh()
    }

    /** Two-way sync with the linked GitHub Project. Shares Res.BOARD with voice so they never interleave. */
    fun sync() {
        val ws = WorkspaceStore.current()
        val link = ws.project ?: run {
            effects.tryEmit(Effect.Toast("Link a GitHub Project first"))
            return
        }
        val start = WorkspaceStore.clock()
        val startIds = ws.cards.map { it.id }.toSet()
        _state.update { it.copy(syncing = true, status = "Syncing with GitHub…", partial = "") }
        val task = tasks.launch("Board sync", Tab.BOARD, setOf(Res.BOARD), dispatchers.io, track = true) {
            try {
                val outcome = BoardSync.Runner(github).run(link, ws.cards, ws.deletedRemote, start)
                WorkspaceStore.updateWorkspace(ws.id) { now ->
                    // Relinked to another project mid-sync: the outcome's ids don't apply.
                    if (now.project != link) return@updateWorkspace now
                    val (cards, tombstones) = BoardSync.merge(
                        now.cards, now.deletedRemote, outcome.cards, outcome.processedDeletes, startIds, start,
                    )
                    now.copy(cards = cards, deletedRemote = tombstones)
                }
                val status = outcome.report.summary(outcome.projectTitle)
                log("Board sync: ${status.replace("\n", " · ")}")
                _state.update { it.copy(status = status) }
                TaskRunner.Done("Board synced", status.lineSequence().first())
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                log("Board sync: $msg")
                _state.update { it.copy(status = "Sync failed: $msg") }
                TaskRunner.Done("Board sync failed", msg)
            } finally {
                main.post {
                    _state.update { it.copy(syncing = false) }
                    syncBusy()
                    refresh()
                    pump()
                }
            }
        }
        if (task == null) {
            _state.update { it.copy(syncing = false, status = "Board is busy — try again") }
        }
    }

    /**
     * Raises [card] as a GitHub issue in the open project's repo. A card already synced as a
     * draft item is converted in place (same project item); otherwise a new issue is created.
     */
    fun raiseIssue(card: WorkspaceStore.Card) {
        if (card.issue != null) return
        val wsId = WorkspaceStore.current().id
        _state.update { it.copy(status = "Raising issue…", partial = "") }
        val task = tasks.launch("Raise issue", Tab.BOARD, setOf(Res.BOARD), dispatchers.io, track = true) {
            try {
                val slug = repoSlug().ifBlank { error("open a project with a GitHub origin first") }
                val link = card.remote
                val issue: CardIssue = if (link != null) {
                    github.convertDraft(link.itemId, github.repositoryId(slug))
                } else {
                    val labels = if (card.type == CardType.BUG) listOf("bug") else emptyList()
                    github.createIssue(slug, card.title, card.body, labels)
                }
                WorkspaceStore.updateWorkspace(wsId) { ws ->
                    ws.copy(
                        cards = ws.cards.map { c ->
                            if (c.id != card.id) return@map c
                            c.copy(issue = issue, remote = c.remote?.copy(contentId = issue.nodeId))
                        }
                    )
                }
                val status = "Raised issue #${issue.number} in $slug"
                log("Board: $status")
                _state.update { it.copy(status = status) }
                TaskRunner.Done("Issue raised", "#${issue.number} ${card.title}")
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                log("Board raise issue: $msg")
                _state.update { it.copy(status = "Raise issue failed: $msg") }
                TaskRunner.Done("Raise issue failed", msg)
            } finally {
                main.post {
                    syncBusy()
                    refresh()
                    pump()
                }
            }
        }
        if (task == null) _state.update { it.copy(status = "Board is busy — try again") }
    }

    fun requestEdit(card: WorkspaceStore.Card) {
        effects.tryEmit(Effect.EditCard(card))
    }

    fun syncBusy() {
        _state.update {
            it.copy(
                agentBusy = tasks.holds(Res.AGENT),
                drafting = tasks.holds(Res.BOARD) && !it.syncing,
            )
        }
    }

    fun setListening(listening: Boolean) {
        _state.update {
            it.copy(
                listening = listening,
                partial = if (listening) it.partial else "",
                status = when {
                    listening -> "Recording…"
                    it.drafting -> "Transcribing…"
                    else -> it.status.takeIf { s -> KEEP_STATUS.any { s.startsWith(it) } }.orEmpty()
                },
            )
        }
    }

    fun setStatus(text: String) {
        _state.update { it.copy(status = text) }
    }

    /** Transcribe [audio] with the provider, then draft cards + REQUIREMENTS.md. */
    fun processRecording(audio: File, creds: Creds) {
        if (creds.key.isBlank()) {
            audio.delete()
            effects.tryEmit(Effect.Toast("Add an API key in Settings, then record again."))
            return
        }
        if (creds.model.isBlank()) {
            audio.delete()
            effects.tryEmit(Effect.Toast("Set a model name in Settings."))
            return
        }
        lastCreds = creds
        pendingAudio.addLast(audio)
        _state.update { it.copy(partial = "", status = "Transcribing…") }
        pump()
    }

    private fun pump() {
        if (pendingAudio.isEmpty() || tasks.holds(Res.BOARD)) return
        val creds = lastCreds ?: return
        val audio = pendingAudio.removeFirst()
        val workspaceId = WorkspaceStore.current().id
        val refs = BoardDraft.refs(WorkspaceStore.current().cards.filter { it.status != CardStatus.DONE })
        val currentReq = RequirementsStore.read(workspaceId)
        val root = session.selectedRoot()
        val task = tasks.launch("Board voice", Tab.BOARD, setOf(Res.BOARD), dispatchers.io, track = true) {
            try {
                _state.update { it.copy(status = "Transcribing…", partial = "") }
                val transcript = AiClient.transcribe(
                    audio,
                    creds.provider,
                    creds.key,
                    creds.base,
                    creds.apiStyle,
                )
                log("Board voice transcript: ${transcript.take(160)}")
                _state.update {
                    it.copy(
                        status = "Heard — drafting…",
                        partial = transcript.take(240),
                    )
                }
                val result = draftFromVoice(transcript, currentReq, refs, creds)
                val (added, moved) = applyDraft(result, workspaceId, root, refs)
                val status = voiceStatus(added, moved, result.requirementsMd.isNotBlank())
                log("Board voice: $status")
                _state.update { it.copy(status = status, partial = transcript.take(240)) }
                TaskRunner.Done("Board updated", status)
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                log("Board voice: $msg")
                effects.tryEmit(Effect.Toast(msg))
                _state.update { it.copy(status = msg, partial = "") }
                TaskRunner.Done("Board voice failed", msg)
            } finally {
                audio.delete()
                main.post {
                    syncBusy()
                    refresh()
                    pump()
                }
            }
        }
        if (task == null) {
            pendingAudio.addFirst(audio)
            return
        }
        syncBusy()
    }

    private fun draftFromVoice(
        transcript: String,
        currentRequirements: String,
        refs: Map<String, WorkspaceStore.Card>,
        creds: Creds,
    ): BoardDraft.Result {
        val boardLines = BoardDraft.boardLines(refs)
        val user = buildString {
            append("Spoken notes (new):\n")
            append(transcript.trim())
            append("\n\nCurrent REQUIREMENTS.md:\n")
            append(currentRequirements.ifBlank { "(empty)" })
            append("\n\nExisting board cards (ref [type · status] title; do not duplicate titles):\n")
            append(boardLines)
        }
        val raw = AiClient.complete(
            PromptStore.get(PromptStore.Kind.BOARD),
            user,
            creds.provider,
            creds.key,
            creds.model,
            creds.base,
            maxUser = 40_000,
            apiStyle = creds.apiStyle,
        )
        return BoardDraft.parse(raw)
    }

    private fun applyDraft(
        draft: BoardDraft.Result,
        workspaceId: String,
        root: File?,
        refs: Map<String, WorkspaceStore.Card>,
    ): Pair<Int, Int> {
        if (draft.requirementsMd.isNotBlank()) {
            RequirementsStore.write(draft.requirementsMd, workspaceId)
            if (root != null && root.isDirectory) {
                runCatching {
                    File(root, "REQUIREMENTS.md").writeText(draft.requirementsMd.trim().take(80_000))
                }
            }
        }
        // Voice ran against a snapshot; if the user switched workspace meanwhile, leave cards alone.
        if (WorkspaceStore.current().id != workspaceId) return 0 to 0
        val existing = BoardDraft.existingTitles(WorkspaceStore.current().cards)
        val fresh = BoardDraft.newCards(draft.cards, existing)
        var added = 0
        for (card in fresh) {
            if (WorkspaceStore.addCard(card.type, card.status, card.title, card.body)) added++
        }
        val moves = BoardDraft.resolveMoves(draft.moves, refs)
        for ((id, status) in moves) WorkspaceStore.moveCard(id, status)
        return added to moves.size
    }

    private fun voiceStatus(added: Int, moved: Int, requirements: Boolean): String {
        val parts = buildList {
            if (added > 0) add("Added $added card${if (added == 1) "" else "s"}")
            if (moved > 0) add("Moved $moved card${if (moved == 1) "" else "s"}")
            if (requirements) add(if (isEmpty()) "Updated requirements" else "requirements updated")
        }
        return parts.joinToString(" · ").ifEmpty { "No board changes" }
    }
}
