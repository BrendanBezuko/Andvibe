package com.example.andvibe.features.board

import android.os.Handler
import android.os.Looper
import com.example.andvibe.AiClient
import com.example.andvibe.ProjectSession
import com.example.andvibe.PromptStore
import com.example.andvibe.Provider
import com.example.andvibe.RequirementsStore
import com.example.andvibe.Tab
import com.example.andvibe.WorkspaceStore
import com.example.andvibe.features.FeatureEffects
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
) {
    data class Creds(val provider: Provider, val key: String, val model: String, val base: String)

    data class State(
        val cards: List<WorkspaceStore.Card> = emptyList(),
        val agentBusy: Boolean = false,
        val listening: Boolean = false,
        val drafting: Boolean = false,
        val partial: String = "",
        val status: String = "",
        val requirements: String = "",
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
                drafting = tasks.holds(Res.BOARD),
                requirements = RequirementsStore.read(ws.id),
            )
        }
    }

    fun cardsIn(column: WorkspaceStore.Column): List<WorkspaceStore.Card> =
        WorkspaceStore.cards(column)

    fun addCard(column: String, title: String, body: String) {
        WorkspaceStore.addCard(column, title, body)
        refresh()
    }

    fun moveCard(id: String, column: String) {
        WorkspaceStore.moveCard(id, column)
        refresh()
    }

    fun deleteCard(id: String) {
        WorkspaceStore.deleteCard(id)
        refresh()
    }

    fun updateCard(id: String, title: String, body: String) {
        WorkspaceStore.updateCard(id, title, body)
        refresh()
    }

    fun buildCard(card: WorkspaceStore.Card) {
        if (tasks.holds(Res.AGENT)) return
        effects.tryEmit(Effect.SendToVibe(instructionFor(card)))
    }

    fun instructionFor(card: WorkspaceStore.Card): String = Companion.instructionFor(card)

    companion object {
        /** Board → Vibe prompt (lead + title + note). */
        fun instructionFor(card: WorkspaceStore.Card): String {
            val lead = when (card.column) {
                WorkspaceStore.Column.BUG.id -> "Fix this bug from the Board."
                WorkspaceStore.Column.SOLUTION.id -> "Implement this solution from the Board."
                WorkspaceStore.Column.COMPLETED.id -> "Revisit this completed Board item."
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
                append("\n\nMake a focused change in the open repo. Do not commit.")
            }
        }
    }

    fun requestEdit(card: WorkspaceStore.Card) {
        effects.tryEmit(Effect.EditCard(card))
    }

    fun syncBusy() {
        _state.update {
            it.copy(
                agentBusy = tasks.holds(Res.AGENT),
                drafting = tasks.holds(Res.BOARD),
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
                    else -> it.status.takeIf { s ->
                        s.startsWith("Added") || s.startsWith("Updated") || s.startsWith("Heard")
                    }.orEmpty()
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
        val existingCards = WorkspaceStore.current().cards
        val currentReq = RequirementsStore.read(workspaceId)
        val root = session.selectedRoot()
        val task = tasks.launch("Board voice", Tab.BOARD, setOf(Res.BOARD), dispatchers.io, track = true) {
            try {
                _state.update { it.copy(status = "Transcribing…", partial = "") }
                val transcript = AiClient.transcribe(audio, creds.provider, creds.key, creds.base)
                log("Board voice transcript: ${transcript.take(160)}")
                _state.update {
                    it.copy(
                        status = "Heard — drafting…",
                        partial = transcript.take(240),
                    )
                }
                val result = draftFromVoice(transcript, currentReq, existingCards, creds)
                val added = applyDraft(result, workspaceId, root)
                val status = when {
                    added > 0 && result.requirementsMd.isNotBlank() ->
                        "Added $added card${if (added == 1) "" else "s"} · requirements updated"
                    added > 0 -> "Added $added card${if (added == 1) "" else "s"}"
                    result.requirementsMd.isNotBlank() -> "Updated requirements"
                    else -> "No new cards"
                }
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
        existingCards: List<WorkspaceStore.Card>,
        creds: Creds,
    ): BoardDraft.Result {
        val boardLines = existingCards
            .filter { it.column != WorkspaceStore.Column.COMPLETED.id }
            .joinToString("\n") { "- [${it.column}] ${it.title}" }
            .ifBlank { "(none)" }
        val user = buildString {
            append("Spoken notes (new):\n")
            append(transcript.trim())
            append("\n\nCurrent REQUIREMENTS.md:\n")
            append(currentRequirements.ifBlank { "(empty)" })
            append("\n\nExisting board cards (do not duplicate titles):\n")
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
        )
        return BoardDraft.parse(raw)
    }

    private fun applyDraft(
        draft: BoardDraft.Result,
        workspaceId: String,
        root: File?,
    ): Int {
        if (draft.requirementsMd.isNotBlank()) {
            RequirementsStore.write(draft.requirementsMd, workspaceId)
            if (root != null && root.isDirectory) {
                runCatching {
                    File(root, "REQUIREMENTS.md").writeText(draft.requirementsMd.trim().take(80_000))
                }
            }
        }
        val existing = BoardDraft.existingTitles(WorkspaceStore.current().cards)
        val fresh = BoardDraft.newCards(draft.cards, existing)
        var added = 0
        for (card in fresh) {
            if (WorkspaceStore.addCard(card.column, card.title, card.body)) added++
        }
        return added
    }
}
