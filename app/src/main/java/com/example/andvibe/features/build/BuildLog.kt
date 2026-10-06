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

    @Synchronized
    fun append(line: String): String {
        val stripped = buffer.append(line)
        publish()
        return stripped
    }

    @Synchronized
    fun replace(content: String) {
        buffer.replace(content)
        publish()
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        publish()
    }

    @Synchronized
    fun snapshot(): String = buffer.text()

    @Synchronized
    private fun publish() {
        _text.value = buffer.text()
        _revision.value = _revision.value + 1
    }
}
