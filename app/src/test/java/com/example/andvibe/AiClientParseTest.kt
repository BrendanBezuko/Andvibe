package com.example.andvibe

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AiClientParseTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun extractJsonStripsFenceAndBom() {
        val raw = "\uFEFF```json\n{\"summary\":\"ok\",\"files\":[]}\n```"
        assertEquals("""{"summary":"ok","files":[]}""", AiClient.extractJson(raw))
    }

    @Test
    fun extractJsonFindsObjectInsideProse() {
        val raw = "Here you go:\n{\"summary\":\"done\",\"files\":[]}\nThanks"
        assertEquals("""{"summary":"done","files":[]}""", AiClient.extractJson(raw))
    }

    @Test
    fun extractJsonRejectsMissingObject() {
        assertThrows(IllegalStateException::class.java) {
            AiClient.extractJson("no json here")
        }
    }

    @Test
    fun parseReadsSummaryAndFileList() {
        val (summary, files) = AiClient.parse(
            """{"summary":"tweaked","files":[{"path":"a.kt","content":"x"}]}""",
        )
        assertEquals("tweaked", summary)
        assertEquals(listOf("a.kt" to "x"), files)
    }

    @Test
    fun parseFilesAcceptsObjectMapAndAliases() {
        val asMap = JSONObject("""{"files":{"src/a.js":"one"}}""")
        assertEquals(listOf("src/a.js" to "one"), AiClient.parseFiles(asMap))

        val aliased = JSONObject(
            """{"files":[{"file":"b.txt","contents":"two"}]}""",
        )
        assertEquals(listOf("b.txt" to "two"), AiClient.parseFiles(aliased))

        assertTrue(AiClient.parseFiles(JSONObject("""{"summary":"x"}""")).isEmpty())
    }

    @Test
    fun applyWritesFilesAndRejectsTraversal() {
        val root = tmp.newFolder("proj")
        val written = AiClient.apply(root, listOf("src/a.txt" to "hello\n"))
        assertEquals(1, written.size)
        assertEquals("hello\n", File(root, "src/a.txt").readText())

        assertThrows(IllegalStateException::class.java) {
            AiClient.apply(root, listOf("../outside.txt" to "nope"))
        }
    }
}
