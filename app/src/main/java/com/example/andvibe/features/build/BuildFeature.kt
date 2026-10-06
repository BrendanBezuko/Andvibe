package com.example.andvibe.features.build

import android.content.Context
import com.example.andvibe.AiClient
import com.example.andvibe.ApkLibrary
import com.example.andvibe.BuildHistory
import com.example.andvibe.BuildService
import com.example.andvibe.DebugLog
import com.example.andvibe.Provider
import com.example.andvibe.Tab
import com.example.andvibe.core.RepoFiles
import com.example.andvibe.tasks.AppDispatchers
import com.example.andvibe.tasks.Res
import com.example.andvibe.tasks.TaskRunner
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class BuildFeature(
    private val app: Context,
    private val tasks: TaskRunner,
    private val dispatchers: AppDispatchers,
    private val session: com.example.andvibe.ProjectSession,
    private val buildService: BuildService,
    val buildLog: BuildLog,
    private val consoleLog: (String) -> Unit,
    private val vibeHistory: () -> java.util.ArrayDeque<Pair<String, String>>,
    private val onVibeFilesChanged: (List<String>) -> Unit,
    private val onGitRefresh: () -> Unit = {},
    private val onBuildChanged: () -> Unit,
    private val onFilesChanged: () -> Unit,
) {
    data class Creds(val provider: Provider, val key: String, val model: String, val base: String)

    data class State(
        val logText: String = "",
        val lastApkPath: String? = null,
        val building: Boolean = false,
        val revising: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private fun note(line: String, mirrorConsole: Boolean = false) {
        buildLog.append(line)
        _state.update { it.copy(logText = buildLog.snapshot()) }
        DebugLog.step("build", line)
        if (mirrorConsole) consoleLog(line)
    }

    fun clearLog() {
        buildLog.clear()
        _state.update { it.copy(logText = "") }
        DebugLog.step("build", "cleared")
    }

    fun setLastApk(path: String?) {
        _state.update { it.copy(lastApkPath = path) }
    }

    fun loadFromStores() {
        val apk = ApkLibrary.list(app).firstOrNull()?.absolutePath
        buildLog.replace(BuildHistory.latestLog())
        _state.update {
            it.copy(
                lastApkPath = apk,
                logText = buildLog.snapshot(),
            )
        }
    }

    fun build(buildUrl: String, buildToken: String) {
        if (tasks.holds(Res.BUILD) || tasks.holds(Res.REVISE)) return
        clearLog()
        _state.update { it.copy(building = true) }
        tasks.launch("Building APK", Tab.BUILD, setOf(Res.BUILD), dispatchers.repo) {
            val root = session.projectRoot()
            val mirrorConsole = File(root, "gradlew").isFile
            note(RepoFiles.display(root, session.reposDir), mirrorConsole)
            val outcome = buildService.run(
                root = root,
                buildUrl = buildUrl,
                buildToken = buildToken,
                mode = BuildService.Mode.AUTO,
                nestClaim = false,
                log = { line -> note(line, mirrorConsole) },
            )
            if (outcome.apk != null) {
                _state.update { it.copy(lastApkPath = outcome.apk.absolutePath) }
            }
            _state.update { it.copy(building = false) }
            syncBusy()
            onBuildChanged()
            TaskRunner.Done(outcome.title, outcome.summary, outcome.apk)
        }
    }

    fun revise(creds: Creds) {
        if (tasks.holds(Res.BUILD) || tasks.holds(Res.REVISE)) return
        val log = buildLog.snapshot()
        if (log.isBlank()) {
            note("Build first. Revise uses that log.")
            return
        }
        if (creds.key.isBlank() || creds.model.isBlank()) {
            val message = "Add an API key in Settings, then tap Revise."
            note(message)
            consoleLog(message)
            return
        }
        _state.update { it.copy(revising = true) }
        val open = session.openFile
        val cwd = session.cwd
        tasks.launch("Revising from build log", Tab.BUILD, setOf(Res.REVISE), dispatchers.agent) {
            var title = "Revise failed"
            var summary = ""
            try {
                val root = session.projectRoot()
                DebugLog.step("revise", "start provider=${creds.provider.id} chars=${log.length}")
                note("Revising from the build log. The API key stays on this phone.", mirrorConsole = true)
                val tail = log.takeLast(12_000)
                val instruction = """
                    The Gradle build failed on Cloud Run. Fix the project so it compiles. Change as little as possible. Cloud Run compiles Kotlin, Java, and Gradle, so edit those files when the log points at them.

                    Build log:
                    $tail
                """.trimIndent()
                val edit = AiClient.edit(
                    root, cwd, open, instruction, creds.provider, creds.key, creds.model, creds.base, vibeHistory()
                )
                note(edit.report, mirrorConsole = true)
                if (edit.written.isNotEmpty()) {
                    val paths = edit.written.map { it.canonicalPath }
                    onVibeFilesChanged(paths)
                    onFilesChanged()
                    onGitRefresh()
                }
                note("Tap Build APK to compile again.", mirrorConsole = true)
                DebugLog.step("revise", "done files=${edit.written.size}")
                title = "Revise finished"
                summary = "${edit.written.size} files changed. Tap Build APK to compile again.\n\n${edit.report}"
            } catch (t: Throwable) {
                DebugLog.step("revise", "fail ${t.javaClass.simpleName}: ${t.message}")
                note("revise failed: ${t.message ?: t.javaClass.simpleName}", mirrorConsole = true)
                summary = t.message ?: t.javaClass.simpleName
            } finally {
                _state.update { it.copy(revising = false) }
                syncBusy()
                onBuildChanged()
            }
            TaskRunner.Done(title, summary)
        }
    }

    fun appendUserMessage(line: String) {
        note(line)
    }

    fun syncBusy() {
        _state.update {
            it.copy(
                building = tasks.holds(Res.BUILD),
                revising = tasks.holds(Res.REVISE),
            )
        }
    }

    fun syncLogFromBuffer() {
        _state.update { it.copy(logText = buildLog.snapshot()) }
    }
}
