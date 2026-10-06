package com.example.andvibe

import com.example.andvibe.features.console.ConsoleCommands
import com.example.andvibe.features.console.ConsoleContext
import com.example.andvibe.features.console.ConsoleLog
import com.example.andvibe.shell.Shell

/**
 * Thin facade for callers still on the console entry point. Prefer [ConsoleFeature] /
 * [ConsoleCommands] with an explicit [ConsoleContext] (Phase 4).
 */
object Console {
    @Volatile
    var bridgeLog: ConsoleLog? = null

    @Volatile
    var shell: Shell? = null

    @Volatile
    var session: ProjectSession? = null

    fun run(line: String) {
        val log = bridgeLog
        if (log != null) {
            ConsoleCommands.run(line, defaultContext(log))
        } else {
            // Pre-graph fallback — should not happen after AppGraph init.
            error("ConsoleLog not wired")
        }
    }

    fun tokenize(input: String): List<String> = ConsoleCommands.tokenize(input)

    private fun defaultContext(log: ConsoleLog): ConsoleContext {
        val activeSession = session ?: error("ProjectSession not wired")
        val activeShell = shell ?: error("Shell not wired")
        val app = activeSession.appContext
        return ConsoleContext(
            session = activeSession,
            log = log,
            appContext = app,
            onOpen = { activeShell.open(it) },
            onPreview = { activeShell.preview(it) },
            onFilesChanged = { activeShell.filesChanged() },
            inWorkspace = { activeSession.inWorkspace(it) },
            projectRoot = { activeSession.projectRoot() },
            activeRepos = { WorkspaceStore.activeRepos() },
            includeProject = { WorkspaceStore.include(it) },
            rememberProject = { ProjectStore.remember(app, it) },
        )
    }
}
