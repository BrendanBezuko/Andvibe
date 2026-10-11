package com.example.andvibe

import androidx.test.core.app.ApplicationProvider
import com.example.andvibe.agent.HttpPost
import java.io.File
import java.util.ArrayDeque
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AiClient.edit end to end; Robolectric because edit reads WorkspaceStore for the prompt and usage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AiClientEditTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        WorkspaceStore.resetForTests()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(ctx.filesDir, "workspaces.json").delete()
        WorkspaceStore.init(ctx)
    }

    @After
    fun tearDown() {
        AiClient.http = AiClient.realHttp
        WorkspaceStore.resetForTests()
    }

    private fun replyWith(content: String, usage: String = "") = HttpPost { _, _, _ ->
        JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content))))
            .apply { if (usage.isNotEmpty()) put("usage", JSONObject(usage)) }
            .toString()
    }

    @Test
    fun editWritesFilesTrimsHistoryAndChargesReportedUsage() {
        val root = tmp.newFolder("repo")
        val files = JSONArray().put(JSONObject().put("path", "src/hello.txt").put("content", "hi\n"))
        AiClient.http = replyWith(
            "```json\n" + JSONObject().put("summary", "added greeting").put("files", files) + "\n```",
            usage = """{"prompt_tokens":100,"completion_tokens":20}""",
        )
        val history = ArrayDeque<Pair<String, String>>()
        repeat(5) { history.addLast("user" to "old $it"); history.addLast("assistant" to "ok") }

        val result = AiClient.edit(root, root, null, "add a greeting", Provider.OPENAI, "k", "m", "", history)

        assertEquals("hi\n", File(root, "src/hello.txt").readText())
        assertEquals("added greeting\nwrote src/hello.txt", result.report)
        assertEquals(8, history.size)
        assertEquals("assistant" to "added greeting", history.last())
        val ws = WorkspaceStore.current()
        assertEquals(100L, ws.inputTokens)
        assertEquals(20L, ws.outputTokens)
    }

    @Test
    fun editEstimatesUsageWhenProviderReportsNone() {
        val root = tmp.newFolder("repo")
        AiClient.http = replyWith("""{"summary":"","files":[]}""")
        val result = AiClient.edit(root, root, null, "noop", Provider.OPENAI, "k", "m", "", ArrayDeque())
        assertEquals("no file changes", result.report)
        assertTrue(WorkspaceStore.current().inputTokens > 0)
        assertTrue(WorkspaceStore.current().outputTokens > 0)
    }
}
