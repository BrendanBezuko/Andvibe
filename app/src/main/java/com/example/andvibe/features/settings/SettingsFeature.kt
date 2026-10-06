package com.example.andvibe.features.settings

import android.content.Context
import com.example.andvibe.DebugMcp
import com.example.andvibe.SecretStore
import com.example.andvibe.features.FeatureEffects
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SettingsFeature(
    private val app: Context,
    private val secrets: SecretStore,
    private val onMcpChanged: () -> Unit,
) {
    data class State(
        val mcpStatus: String = "",
        val plainSecrets: Boolean = false,
    )

    sealed interface Effect {
        data object McpChanged : Effect
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val effects = FeatureEffects<Effect>()
    val effectsFlow = effects.events

    fun refresh() {
        _state.update {
            it.copy(
                mcpStatus = DebugMcp.statusText(),
                plainSecrets = secrets.plain,
            )
        }
    }

    fun setMcpEnabled(enabled: Boolean) {
        DebugMcp.setEnabled(app, enabled)
        refresh()
        effects.tryEmit(Effect.McpChanged)
        onMcpChanged()
    }
}
