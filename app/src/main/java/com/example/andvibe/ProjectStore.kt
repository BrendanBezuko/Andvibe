package com.example.andvibe

import android.content.Context
import java.io.File

object ProjectStore {
    private const val PREF = "andvibe_projects"
    private const val LAST = "last"

    fun remember(context: Context, dir: File) {
        val repos = File(context.applicationContext.filesDir, "repos").canonicalFile
        val canon = dir.canonicalFile
        if (canon != repos && !canon.path.startsWith(repos.path + File.separator)) return
        if (canon == repos) return
        context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(LAST, canon.absolutePath)
            .apply()
    }

    fun restore(context: Context, repos: File): File? {
        val path = context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(LAST, null) ?: return null
        val dir = File(path)
        if (!dir.isDirectory) return null
        val canon = dir.canonicalFile
        val root = repos.canonicalFile
        if (canon != root && !canon.path.startsWith(root.path + File.separator)) return null
        if (canon == root) return null
        return canon
    }
}
