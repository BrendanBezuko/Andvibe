package com.example.andvibe.features

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Buffered one-shot effects (DESIGN.md §3.4) — delivered exactly once when collected. */
class FeatureEffects<E> {
    private val channel = Channel<E>(Channel.BUFFERED)
    val events: Flow<E> = channel.receiveAsFlow()

    suspend fun emit(effect: E) {
        channel.send(effect)
    }

    fun tryEmit(effect: E) {
        channel.trySend(effect)
    }
}
