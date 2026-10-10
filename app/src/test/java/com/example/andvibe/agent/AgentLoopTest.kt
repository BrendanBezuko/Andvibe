package com.example.andvibe.agent

import com.example.andvibe.Provider
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentLoopTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun repos(): File = tmp.newFolder("repos")

    private fun project(repos: File): File =
        File(repos, "proj").also {
            it.mkdirs()
            File(it, "README.md").writeText("hi\n")
        }

    private fun tools(): ToolRegistry =
        ToolRegistry(build = { error("build not expected") }, includeProject = {})

    private fun request(
        root: File,
        repos: File,
        task: String = "list files",
        stop: AtomicBoolean = AtomicBoolean(false),
        http: HttpPost,
    ): AgentRequest = AgentRequest(
        ctx = AgentContext(root, repos),
        cwd = root,
        open = null,
        task = task,
        provider = Provider.ANTHROPIC,
        key = "test-key",
        model = "claude-test",
        base = "https://api.anthropic.com",
        earlier = emptyList(),
        workspaceRepos = listOf(root),
        stop = stop,
        http = http,
    )

    private fun textTurn(text: String): String =
        JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .put("usage", JSONObject().put("input_tokens", 1).put("output_tokens", 1))
            .toString()

    private fun toolTurn(name: String, id: String = "t1", args: JSONObject = JSONObject()): String =
        JSONObject()
            .put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", "working"))
                    .put(
                        JSONObject()
                            .put("type", "tool_use")
                            .put("id", id)
                            .put("name", name)
                            .put("input", args),
                    ),
            )
            .put("usage", JSONObject().put("input_tokens", 2).put("output_tokens", 2))
            .toString()

    @Test
    fun validatesRequestBeforeCallingModel() {
        val repos = repos()
        val root = project(repos)
        val http = HttpPost { _, _, _ -> error("should not call") }
        val base = request(root, repos, http = http)

        fun expectFail(req: AgentRequest) {
            assertThrows(IllegalStateException::class.java) {
                runBlocking { AgentLoop.run(req, tools(), {}) }
            }
        }
        expectFail(base.copy(key = ""))
        expectFail(base.copy(model = ""))
        expectFail(base.copy(model = "bad name"))
        expectFail(base.copy(base = "ftp://x"))
        expectFail(base.copy(task = "x".repeat(16_001)))
    }

    @Test
    fun planThenExecuteEmitsDoneText() = runBlocking {
        val repos = repos()
        val root = project(repos)
        var calls = 0
        val http = HttpPost { _, _, _ ->
            calls++
            when (calls) {
                1 -> textTurn("Plan: read README.")
                else -> textTurn("Done reading.")
            }
        }
        val events = mutableListOf<AgentEvent>()
        val result = AgentLoop.run(
            request(root, repos, http = http),
            tools(),
        ) { events.add(it) }

        assertEquals("Done reading.", result.text)
        assertFalse(result.stopped)
        assertTrue(events.any { it is AgentEvent.Step && it.text.contains("Planning") })
        assertTrue(events.any { it is AgentEvent.Usage })
        assertTrue(calls >= 2)
    }

    @Test
    fun stopDuringPlanningReturnsStopped() = runBlocking {
        val repos = repos()
        val root = project(repos)
        val stop = AtomicBoolean(false)
        var calls = 0
        val http = HttpPost { _, _, _ ->
            calls++
            stop.set(true)
            textTurn("partial plan")
        }
        val result = AgentLoop.run(
            request(root, repos, stop = stop, http = http),
            tools(),
        ) {}

        assertTrue(result.stopped)
        assertTrue(result.text.contains("Stopped during planning"))
        assertEquals(1, calls)
    }

    @Test
    fun writeToolEmitsFilesChanged() = runBlocking {
        val repos = repos()
        val root = project(repos)
        var calls = 0
        val http = HttpPost { _, _, _ ->
            calls++
            when (calls) {
                1 -> textTurn("Plan: write note.")
                2 -> toolTurn(
                    "write_file",
                    args = JSONObject().put("path", "note.txt").put("content", "n\n"),
                )
                else -> textTurn("Wrote note.")
            }
        }
        val events = mutableListOf<AgentEvent>()
        val result = AgentLoop.run(
            request(root, repos, task = "add note.txt", http = http),
            tools(),
        ) { events.add(it) }

        assertTrue(File(root, "note.txt").isFile)
        assertTrue(events.any { it is AgentEvent.FilesChanged })
        assertTrue(result.changed.any { it.name == "note.txt" })
        assertEquals("Wrote note.", result.text)
    }
}
