package com.example.andvibe.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** agent/ must not reference AppState or UiBridge (DESIGN.md Phase 3 exit). */
class AgentPurityTest {
    @Test
    fun agentHasNoAppStateOrUiBridge() {
        val dir = sequenceOf(
            "src/main/java/com/example/andvibe/agent",
            "app/src/main/java/com/example/andvibe/agent",
        ).map(::File).firstOrNull { it.isDirectory }
        assertTrue("agent source dir not found from ${File(".").absolutePath}", dir != null)
        val offenders = dir!!.walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                        return@mapIndexedNotNull null
                    }
                    when {
                        trimmed.startsWith("import com.example.andvibe.AppState") ->
                            "${file.name}:${i + 1} $line"
                        trimmed.startsWith("import com.example.andvibe.UiBridge") ->
                            "${file.name}:${i + 1} $line"
                        Regex("""\bAppState\b""").containsMatchIn(trimmed) ->
                            "${file.name}:${i + 1} $line"
                        Regex("""\bUiBridge\b""").containsMatchIn(trimmed) ->
                            "${file.name}:${i + 1} $line"
                        else -> null
                    }
                }
            }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
