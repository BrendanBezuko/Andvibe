package com.example.andvibe

import android.content.Context
import com.example.andvibe.core.RepoFiles
import java.io.File

/**
 * Owns the open project: the repos dir, the working directory, and the open file.
 * The single owner for project/workspace scoping.
 */
class ProjectSession internal constructor(
    val reposDir: File,
    private val context: Context?,
    private val onGitInvalidate: () -> Unit = {},
    private val onProjectChanged: () -> Unit = {},
) {
    constructor(
        context: Context,
        onGitInvalidate: () -> Unit = {},
        onProjectChanged: () -> Unit = {},
    ) : this(
        File(context.applicationContext.filesDir, "repos").apply { mkdirs() },
        context.applicationContext,
        onGitInvalidate,
        onProjectChanged,
    )

    /** JVM tests build a session from a temp dir with no Context; they must not reach this. */
    val appContext: Context get() = context ?: error("ProjectSession has no Android context")

    @Volatile var cwd: File = reposDir
    @Volatile var openFile: File? = null

    fun restoreLastProject() {
        cwd = ProjectStore.restore(appContext, reposDir)?.takeIf { WorkspaceStore.contains(it) } ?: reposDir
    }

    fun projectRoot(): File = RepoFiles.projectRoot(cwd, reposDir)

    /** Open project for Vibe / Understand / PROJECT bar. Prefers cwd, then last remembered. */
    fun selectedRoot(): File? {
        val open = runCatching { projectRoot() }.getOrNull()
        if (open != null && open.canonicalFile != reposDir.canonicalFile && WorkspaceStore.contains(open)) {
            return open
        }
        val remembered = ProjectStore.restore(appContext, reposDir)
            ?: return WorkspaceStore.activeRepos().singleOrNull()
        val root = runCatching { RepoFiles.projectRoot(remembered, reposDir) }.getOrNull()
            ?: remembered.takeIf { it.isDirectory }
        if (root != null && root.canonicalFile != reposDir.canonicalFile && WorkspaceStore.contains(root)) {
            return root
        }
        return WorkspaceStore.activeRepos().singleOrNull()
    }

    /** Reset cwd to repos root when the open path is outside the workspace. */
    fun fitWorkspace(toRoot: Boolean = false): Boolean {
        if (!toRoot && WorkspaceStore.contains(cwd)) return false
        if (cwd.canonicalFile == reposDir.canonicalFile) return false
        cwd = reposDir
        openFile = null
        onGitInvalidate()
        onProjectChanged()
        return true
    }

    fun inWorkspace(file: File): File {
        if (!WorkspaceStore.contains(file)) {
            error("${RepoFiles.display(file, reposDir)} is not in this workspace")
        }
        return file
    }
}
