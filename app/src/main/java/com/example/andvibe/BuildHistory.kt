package com.example.andvibe

import android.content.Context
import org.json.JSONObject
import java.io.File

object BuildHistory {
    data class Entry(
        val id: String,
        val repo: String,
        val started: Long,
        val ok: Boolean,
        val apk: String,
        val summary: String
    )

    private const val MAX_ENTRIES = 40
    private const val MAX_LOG = 200_000

    @Volatile var version = 0
        private set

    @Volatile
    private var filesDir: File? = null

    @Volatile
    var onBuildChanged: (() -> Unit)? = null

    fun init(context: Context) {
        filesDir = context.applicationContext.filesDir
    }

    fun record(repo: String, started: Long, ok: Boolean, apk: String?, summary: String, log: String) {
        try {
            val folder = folder(WorkspaceStore.current().id)
            val id = started.toString()
            File(folder, "$id.log").writeText(log.takeLast(MAX_LOG))
            File(folder, "$id.json").writeText(
                JSONObject()
                    .put("repo", repo)
                    .put("started", started)
                    .put("ok", ok)
                    .put("apk", apk.orEmpty())
                    .put("summary", summary.take(300))
                    .toString()
            )
            list().drop(MAX_ENTRIES).forEach { delete(it) }
        } catch (t: Throwable) {
            DebugLog.step("build", "history save failed: ${t.message ?: t.javaClass.simpleName}")
        }
        version++
        onBuildChanged?.invoke()
    }

    fun list(): List<Entry> {
        val folder = folder(WorkspaceStore.current().id)
        return folder.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.mapNotNull { file ->
                runCatching {
                    val json = JSONObject(file.readText())
                    Entry(
                        id = file.name.removeSuffix(".json"),
                        repo = json.optString("repo"),
                        started = json.optLong("started"),
                        ok = json.optBoolean("ok"),
                        apk = json.optString("apk"),
                        summary = json.optString("summary")
                    )
                }.getOrNull()
            }
            ?.sortedByDescending { it.started }
            .orEmpty()
    }

    fun log(entry: Entry): String {
        val file = File(folder(WorkspaceStore.current().id), "${entry.id}.log")
        return if (file.isFile) runCatching { file.readText() }.getOrDefault("") else ""
    }

    fun latestLog(): String = list().firstOrNull()?.let { log(it) }.orEmpty()

    fun delete(entry: Entry) {
        val folder = folder(WorkspaceStore.current().id)
        File(folder, "${entry.id}.json").delete()
        File(folder, "${entry.id}.log").delete()
        version++
    }

    fun forget(workspace: String) {
        if (workspace.isBlank()) return
        File(File(requireFilesDir(), "builds"), workspace).deleteRecursively()
    }

    private fun folder(workspace: String): File {
        return File(File(requireFilesDir(), "builds"), workspace).apply { mkdirs() }
    }

    private fun requireFilesDir(): File =
        filesDir ?: error("BuildHistory.init not called")
}
