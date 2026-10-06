package com.example.andvibe.features.board

import com.example.andvibe.WorkspaceStore
import com.example.andvibe.features.FeatureEffects
import com.example.andvibe.tasks.Res
import com.example.andvibe.tasks.TaskRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class BoardFeature(
    private val tasks: TaskRunner,
) {
    data class State(
        val cards: List<WorkspaceStore.Card> = emptyList(),
        val agentBusy: Boolean = false,
    )

    sealed interface Effect {
        data class SendToVibe(val instruction: String) : Effect
        data class EditCard(val card: WorkspaceStore.Card) : Effect
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val effects = FeatureEffects<Effect>()
    val effectsFlow = effects.events

    fun refresh() {
        val ws = WorkspaceStore.current()
        _state.update {
            it.copy(
                cards = ws.cards,
                agentBusy = tasks.holds(Res.AGENT),
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
        _state.update { it.copy(agentBusy = tasks.holds(Res.AGENT)) }
    }
}
