package com.example.andvibe

import android.content.Context
import java.io.File

/** Workspace-scoped REQUIREMENTS.md written by Board voice capture. */
object RequirementsStore {
    private const val MAX_CHARS = 80_000

    @Volatile
    private var filesDir: File? = null

    fun init(context: Context) {
        filesDir = context.applicationContext.filesDir
    }

    fun read(workspaceId: String = WorkspaceStore.current().id): String {
        if (workspaceId.isBlank()) return ""
        val file = file(workspaceId)
        if (!file.isFile) return ""
        return runCatching { file.readText() }.getOrDefault("")
    }

    fun write(markdown: String, workspaceId: String = WorkspaceStore.current().id) {
        if (workspaceId.isBlank()) return
        val text = markdown.trim().take(MAX_CHARS)
        val target = file(workspaceId)
        if (text.isEmpty()) {
            target.delete()
            return
        }
        target.parentFile?.mkdirs()
        target.writeText(text)
    }

    fun forget(workspaceId: String) {
        if (workspaceId.isBlank()) return
        File(File(requireFilesDir(), "docs"), workspaceId).deleteRecursively()
    }

    fun file(workspaceId: String): File =
        File(File(File(requireFilesDir(), "docs"), workspaceId), "REQUIREMENTS.md")

    private fun requireFilesDir(): File =
        filesDir ?: error("RequirementsStore.init not called")
}
