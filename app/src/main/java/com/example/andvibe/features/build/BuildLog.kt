package com.example.andvibe.features.build

import com.example.andvibe.core.BoundedLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bounded build log exposed as [StateFlow] for UI collection (DESIGN.md §3.4). */
class BuildLog(
    private val buffer: BoundedLog = BoundedLog(),
) {
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private var lastPublishMs = 0L
    private val publishMinMs = 120L

    @Synchronized
    fun append(line: String): String {
        val stripped = buffer.append(line)
        val now = System.currentTimeMillis()
        if (now - lastPublishMs >= publishMinMs) {
            lastPublishMs = now
            publish()
        }
        return stripped
    }

    /** Push any buffered lines to collectors (call after a burst ends). */
    @Synchronized
    fun flush() {
        lastPublishMs = System.currentTimeMillis()
        publish()
    }

    @Synchronized
    fun replace(content: String) {
        buffer.replace(content)
        flush()
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        flush()
    }

    @Synchronized
    fun snapshot(): String = buffer.text()

    @Synchronized
    private fun publish() {
        _text.value = buffer.text()
        _revision.value = _revision.value + 1
    }
}
