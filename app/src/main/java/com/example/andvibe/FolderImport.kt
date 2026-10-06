package com.example.andvibe

import com.example.andvibe.core.GitClient
import com.example.andvibe.core.RepoFiles

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

object FolderImport {
    private const val MAX_ENTRY = 32L * 1024 * 1024
    private const val MAX_TOTAL = 200L * 1024 * 1024
    private val skip = setOf("node_modules", ".gradle", "build", "dist", "out", "__MACOSX", ".idea")
    private val cols = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    )

    fun displayName(context: Context, treeUri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: docId.substringAfterLast('/').substringAfterLast(':')
        } catch (_: Exception) {
            "project"
        }.ifBlank { "project" }
    }

    fun importTree(context: Context, treeUri: Uri, repos: File, log: (String) -> Unit): File {
        val raw = displayName(context, treeUri)
        val dest = uniqueDir(repos, raw)
        DebugLog.step("import", "start name=$raw dest=${dest.name}")
        log("importing $raw")
        val counter = Counter()
        val root = dest.canonicalFile
        try {
            dest.mkdirs()
            val treeId = DocumentsContract.getTreeDocumentId(treeUri)
            copyChildren(context, treeUri, treeId, dest, root, 0, counter)
        } catch (t: Throwable) {
            DebugLog.step("import", "fail ${t.javaClass.simpleName}: ${t.message}")
            dest.deleteRecursively()
            throw IllegalStateException(t.message ?: "couldn't open that folder")
        }
        if (counter.skipped > 0) {
            log("skipped ${counter.skipped} heavy folders (node_modules, build, .gradle)")
        }
        if (!File(dest, ".git").exists()) {
            log("no .git in this folder. Init on the Git tab if you want history.")
        }
        DebugLog.step("import", "done dest=${dest.name} skipped=${counter.skipped} git=${File(dest, ".git").exists()}")
        log("opened ~/${dest.name}")
        return dest
    }

    private fun uniqueDir(repos: File, raw: String): File {
        val base = runCatching { GitClient.safeRepoName(raw) }.getOrDefault("project")
        var name = base
        var n = 2
        var dir = File(repos, name)
        while (n < 50 && dir.exists() && !dir.list().isNullOrEmpty()) {
            name = "$base-$n"
            dir = File(repos, name)
            n++
        }
        if (dir.exists() && !dir.list().isNullOrEmpty()) error("too many copies of $base")
        if (dir.exists()) dir.deleteRecursively()
        return dir
    }

    private fun copyChildren(
        context: Context,
        treeUri: Uri,
        parentId: String,
        dest: File,
        root: File,
        depth: Int,
        counter: Counter
    ) {
        if (depth > 40) error("folder is nested too deep")
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val cursor = context.contentResolver.query(children, cols, null, null, null)
            ?: error("couldn't list that folder")
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                if (name == "." || name == ".." || name.contains("/") || name.contains("\\") || name.contains("\u0000")) {
                    continue
                }
                if (name in skip) {
                    counter.skipped++
                    continue
                }
                val child = File(dest, name)
                val mime = it.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    child.mkdirs()
                    RepoFiles.ensureInside(root, child)
                    copyChildren(context, treeUri, id, child, root, depth + 1, counter)
                } else {
                    child.parentFile?.mkdirs()
                    RepoFiles.ensureInside(root, child)
                    copyFile(context, treeUri, id, child, counter)
                }
            }
        }
    }

    private fun copyFile(context: Context, treeUri: Uri, docId: String, dest: File, counter: Counter) {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val input = context.contentResolver.openInputStream(uri) ?: error("couldn't read ${dest.name}")
        input.use { source ->
            dest.outputStream().use { output ->
                val buf = ByteArray(8192)
                var entry = 0L
                while (true) {
                    val n = source.read(buf)
                    if (n < 0) break
                    entry += n
                    counter.total += n
                    if (entry > MAX_ENTRY || counter.total > MAX_TOTAL) {
                        error("folder is too large to open on this phone")
                    }
                    output.write(buf, 0, n)
                }
            }
        }
    }

    private class Counter {
        var total = 0L
        var skipped = 0
    }
}
