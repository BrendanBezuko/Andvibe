package com.example.andvibe

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ApkLibrary {
    fun dir(context: Context): File {
        val base = base(context)
        val folder = File(base, WorkspaceStore.current().id)
        folder.mkdirs()
        base.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".apk") }
            ?.forEach { it.renameTo(File(folder, it.name)) }
        return folder
    }

    fun forget(context: Context, workspace: String) {
        if (workspace.isBlank()) return
        File(base(context), workspace).deleteRecursively()
    }

    private fun base(context: Context): File {
        val folder = context.getExternalFilesDir("apk") ?: File(context.filesDir, "apk")
        folder.mkdirs()
        return folder
    }

    fun place(context: Context, label: String): File {
        val folder = dir(context)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val base = label
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
            .take(48)
            .ifBlank { "app" }
        var dest = File(folder, "$base-$stamp.apk")
        var n = 2
        while (dest.exists()) {
            dest = File(folder, "$base-$stamp-$n.apk")
            n++
        }
        return dest
    }

    fun list(context: Context): List<File> {
        return dir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".apk") && it.length() > 0L }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }
}
