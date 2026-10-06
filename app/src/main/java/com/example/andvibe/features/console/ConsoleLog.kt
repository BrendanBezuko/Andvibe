package com.example.andvibe.features.console

import com.example.andvibe.core.BoundedLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Console tape buffer with cheap observation (DESIGN.md §3.4). */
class ConsoleLog {
    private val buffer = BoundedLog()
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun text(): String = buffer.text()

    fun append(line: String): String {
        val text = buffer.append(line)
        _revision.value = _revision.value + 1
        return text
    }

    fun clear() {
        buffer.clear()
        _revision.value = _revision.value + 1
    }

    fun replace(text: String) {
        buffer.replace(text)
        _revision.value = _revision.value + 1
    }
}
