package com.example.andvibe.agent

import java.io.File

/**
 * Per-run agent context. No global app state or UI listener bus (DESIGN.md Phase 3 exit).
 * Build and workspace side effects go through [ToolRegistry] injectables.
 */
class AgentContext(
    @Volatile var root: File?,
    val repos: File,
    /** Fired when create_project switches the working repo mid-run. */
    var onRootChanged: (File) -> Unit = {},
) {
    val changed = linkedSetOf<File>()

    val repo: File
        get() = root ?: error(
            "no repo is selected. Ask the user to pick one in the bottom PROJECT bar, " +
                "or call create_project if they asked for a new project."
        )
}
