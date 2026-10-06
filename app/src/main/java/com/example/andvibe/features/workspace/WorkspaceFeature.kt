package com.example.andvibe.features.workspace

import com.example.andvibe.WorkspaceStore
import com.example.andvibe.features.FeatureEffects
import com.example.andvibe.tasks.Res
import com.example.andvibe.tasks.TaskRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class WorkspaceFeature(
    private val tasks: TaskRunner,
) {
    data class State(
        val workspaces: List<WorkspaceStore.Workspace> = emptyList(),
        val currentId: String = "",
        val currentName: String = "",
        val repos: List<String> = emptyList(),
        val switchBlockedBy: String? = null,
    )

    sealed interface Effect {
        data class Switched(val name: String) : Effect
        data class Refused(val reason: String) : Effect
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val effects = FeatureEffects<Effect>()
    val effectsFlow = effects.events

    fun refresh() {
        val current = WorkspaceStore.current()
        val blocked = when {
            tasks.holds(Res.AGENT) -> tasks.newest()?.label ?: "Agent working"
            tasks.holds(Res.BUILD) || tasks.holds(Res.REVISE) || tasks.holds(Res.UNDERSTAND) ->
                tasks.newest()?.label
            else -> null
        }
        _state.update {
            it.copy(
                workspaces = WorkspaceStore.workspaces(),
                currentId = current.id,
                currentName = current.name,
                repos = current.repos.sorted(),
                switchBlockedBy = blocked,
            )
        }
    }

    fun canSwitch(): Boolean = _state.value.switchBlockedBy == null

    fun switchTo(id: String) {
        refresh()
        val blocked = _state.value.switchBlockedBy
        if (blocked != null) {
            effects.tryEmit(Effect.Refused("Wait for $blocked to finish"))
            return
        }
        if (!WorkspaceStore.select(id)) return
        refresh()
        effects.tryEmit(Effect.Switched(_state.value.currentName))
    }

    fun create(name: String) {
        WorkspaceStore.create(name)
        refresh()
    }

    fun renameCurrent(name: String) {
        WorkspaceStore.rename(name)
        refresh()
    }

    fun delete(id: String) {
        WorkspaceStore.delete(id)
        refresh()
    }

    fun setRepoIncluded(name: String, included: Boolean) {
        WorkspaceStore.setRepo(name, included)
        refresh()
    }
}
