package com.example.andvibe.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** agent/ must not reference deleted global state or the old UI listener bus (DESIGN.md Phase 3/5). */
class AgentPurityTest {
    @Test
    fun agentHasNoLegacyGlobals() {
        val banned = listOf("App" + "State", "Ui" + "Bridge")
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
                    banned.firstOrNull { name ->
                        trimmed.startsWith("import com.example.andvibe.$name") ||
                            Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(trimmed)
                    }?.let { "${file.name}:${i + 1} $line" }
                }
            }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
