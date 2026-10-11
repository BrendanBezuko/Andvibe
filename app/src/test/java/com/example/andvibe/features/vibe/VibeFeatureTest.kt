package com.example.andvibe.features.vibe

import com.example.andvibe.AiClient
import com.example.andvibe.ChatStore
import com.example.andvibe.MemoryKeyValueStore
import com.example.andvibe.ProjectSession
import com.example.andvibe.Provider
import com.example.andvibe.Tab
import com.example.andvibe.agent.AgentRuntime
import com.example.andvibe.agent.HttpPost
import com.example.andvibe.agent.ToolRegistry
import com.example.andvibe.tasks.Res
import com.example.andvibe.tasks.TaskRunner
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A real VibeFeature on the JVM: injected ChatStore/prefs, a real TaskRunner + AgentRuntime,
 * and the model faked at AiClient's HTTP seam. No Robolectric, no singleton resets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VibeFeatureTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val usage = mutableListOf<Triple<String, Long, Long>>()
    private val prefs = MemoryKeyValueStore()
    private var workspace = "ws1"
    private val modelCalls = mutableListOf<JSONObject>()

    @After
    fun restore() {
        AiClient.http = AiClient.realHttp
    }

    /** Anthropic Messages reply with no tool calls, so the agent finishes in one step. */
    private fun replyWith(text: String) {
        AiClient.http = HttpPost { _, _, body ->
            modelCalls += JSONObject(body)
            JSONObject()
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                .put("stop_reason", "end_turn")
                .put("usage", JSONObject().put("input_tokens", 12).put("output_tokens", 5))
                .toString()
        }
    }

    private class Built(val feature: VibeFeature, val chats: ChatStore, val tasks: TaskRunner)

    private fun TestScope.build(): Built {
        val lane = StandardTestDispatcher(testScheduler)
        var feature: VibeFeature? = null
        // As in the app: TaskRunner changes → shell.busyChanged → MainActivity → vibeFeature.syncBusy().
        val tasks = TaskRunner(scope = this, post = { it.run() }, serviceSync = {}, notifyDone = { _, _ -> }, onChanged = { feature?.syncBusy() })
        val runtime = AgentRuntime(
            tools = ToolRegistry(build = { "no build in tests" }, includeProject = {}),
            launchAgent = { block -> tasks.launch("Agent working", Tab.VIBE, setOf(Res.AGENT), on = lane, block = block) },
        )
        val chats = ChatStore(File(tmp.root, "chats"), { workspace })
        val repos = File(tmp.root, "repos").apply { mkdirs() }
        val built = VibeFeature(
            prefs = prefs,
            chats = chats,
            agentRuntime = runtime,
            tasks = tasks,
            session = ProjectSession(repos, null),
            onFilesChanged = {},
            onGitUpdate = {},
            onProjectChanged = {},
            log = {},
            gitBusy = { false },
            refreshGitSnapshot = {},
            addUsage = { model, input, output -> usage += Triple(model, input, output) },
            rememberProject = {},
        )
        feature = built
        built.syncWorkspace()
        return Built(built, chats, tasks)
    }

    private fun send(f: VibeFeature, text: String, repos: File = File(tmp.root, "repos")) = f.send(
        text,
        VibeFeature.Creds(Provider.ANTHROPIC, "key", "claude-test", ""),
        VibeFeature.SendContext(startRoot = null, cwd = repos, open = null, workspaceRepos = emptyList()),
    )

    @Test
    fun sendRunsAgentAndPersistsTheConversation() = runTest {
        replyWith("Done — nothing to change.")
        val b = build()
        b.feature.setDraft("fix the login bug")

        send(b.feature, "fix the login bug")
        assertTrue("busy while the agent runs", b.feature.state.value.busy)
        assertEquals(listOf("user" to "fix the login bug"), b.feature.state.value.chat)
        advanceUntilIdle()

        val state = b.feature.state.value
        assertFalse(state.busy)
        assertEquals("user" to "fix the login bug", state.chat.first())
        assertEquals("assistant", state.chat.last().first)
        assertTrue(state.chat.last().second.startsWith("Done — nothing to change."))
        assertEquals(listOf("user", "assistant"), state.history.map { it.first })
        assertEquals("", state.draftPrompt)
        assertEquals("", prefs.getString("draft_vibe_prompt"))
        // Two model calls by design (plan, then execute); each one is charged once.
        assertEquals(2, modelCalls.size)
        assertEquals(List(2) { Triple("claude-test", 12L, 5L) }, usage)
        assertTrue(modelCalls.all { it.getJSONArray("messages").toString().contains("fix the login bug") })
        assertFalse(b.tasks.holds(Res.AGENT))

        // Persisted: a fresh store over the same directory sees the chat.
        val reloaded = ChatStore(File(tmp.root, "chats"), { workspace })
        assertEquals(state.chat, reloaded.syncWorkspace(emptyList(), emptyList())!!.turns)
    }

    @Test
    fun modelErrorEndsTurnWithMessageAndFreesTheAgent() = runTest {
        AiClient.http = HttpPost { _, _, _ -> error("HTTP 401: invalid x-api-key") }
        val b = build()
        send(b.feature, "hello")
        advanceUntilIdle()
        val state = b.feature.state.value
        assertFalse(state.busy)
        assertTrue(state.chat.last().second.contains("invalid x-api-key"))
        assertFalse(b.tasks.holds(Res.AGENT))
    }

    @Test
    fun draftSurvivesRestart() = runTest {
        build().feature.setDraft("half-written idea")
        assertEquals("half-written idea", build().feature.state.value.draftPrompt)
    }

    @Test
    fun newChatThenReopenAndWorkspaceSwitch() = runTest {
        replyWith("ok")
        val b = build()
        send(b.feature, "first task")
        advanceUntilIdle()
        val firstChat = b.feature.state.value.chat
        val firstId = b.feature.activeChatId()

        assertTrue(b.feature.startNewChat())
        assertTrue(b.feature.state.value.chat.isEmpty())
        assertEquals(1, b.feature.savedChats().size)

        assertTrue(b.feature.openChat(firstId))
        assertEquals(firstChat, b.feature.state.value.chat)

        workspace = "ws2"
        assertTrue(b.feature.syncWorkspace())
        assertTrue(b.feature.state.value.chat.isEmpty())
        workspace = "ws1"
        assertTrue(b.feature.syncWorkspace())
        assertEquals(firstChat, b.feature.state.value.chat)
    }

    @Test
    fun chatActionsAreRefusedWhileAgentRuns() = runTest {
        replyWith("ok")
        val b = build()
        send(b.feature, "long task")
        assertFalse(b.feature.startNewChat())
        assertFalse(b.feature.syncWorkspace())
        advanceUntilIdle()
        assertTrue(b.feature.startNewChat())
    }

    @Test
    fun commitAddsStepsThenAssistant() {
        val out = VibeFeature.commitAssistantReply(listOf("user" to "fix bug"), listOf("read file", "edit file"), "Fixed.")
        assertEquals(3, out.size)
        assertEquals("steps", out[1].first)
        assertTrue(out[1].second.contains("read file"))
        assertEquals("assistant" to "Fixed.", out[2])
    }

    @Test
    fun commitSkipsEmptyStepsAndKeepsBlankResult() {
        assertEquals(listOf("user" to "hi", "assistant" to "Hello"), VibeFeature.commitAssistantReply(listOf("user" to "hi"), emptyList(), "Hello"))
        assertEquals("assistant" to "", VibeFeature.commitAssistantReply(listOf("user" to "go"), listOf("step"), "").last())
    }
}
