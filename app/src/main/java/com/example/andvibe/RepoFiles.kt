package com.example.andvibe

import java.io.File

object RepoFiles {
    val SKIP_DIRS = setOf(
        ".git", "node_modules", "build", "dist", ".gradle", ".idea", "out", "__MACOSX"
    )
    val JS_EXT = setOf("js", "mjs", "cjs")

    fun rel(file: File, root: File): String {
        val base = root.canonicalFile.path
        val child = file.canonicalFile.path
        return when {
            child == base -> ""
            child.startsWith(base + File.separator) ->
                child.substring(base.length + 1).replace(File.separatorChar, '/')
            else -> file.name
        }
    }

    fun display(cwd: File, repos: File): String {
        val path = rel(cwd, repos)
        return if (path.isEmpty()) "~" else "~/$path"
    }

    fun ensureInside(root: File, file: File) {
        val base = root.canonicalFile
        val child = file.canonicalFile
        if (child != base && !child.path.startsWith(base.path + File.separator)) {
            error("outside repos")
        }
    }

    fun resolve(cwd: File, repos: File, raw: String): File {
        val arg = raw.trim()
        val target = when {
            arg.isEmpty() || arg == "~" -> repos
            arg.startsWith("~/") -> File(repos, arg.removePrefix("~/"))
            arg.startsWith("/") -> File(repos, arg.trimStart('/'))
            else -> File(cwd, arg)
        }
        val canon = target.canonicalFile
        ensureInside(repos, canon)
        return canon
    }

    fun safeChild(root: File, relative: String): File {
        val rel = relative.trim().replace('\\', '/')
        if (rel.isEmpty() || rel.startsWith("/")) error("bad path: $relative")
        val parts = rel.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." || it == ".git" }) {
            error("bad path: $relative")
        }
        val file = File(root, parts.joinToString(File.separator))
        file.parentFile?.mkdirs()
        val canon = file.canonicalFile
        ensureInside(root, canon)
        return canon
    }

    fun looksBinary(file: File): Boolean {
        if (!file.isFile) return false
        file.inputStream().use { input ->
            val buf = ByteArray(1024)
            val n = input.read(buf)
            for (i in 0 until n) {
                if (buf[i] == 0.toByte()) return true
            }
        }
        return false
    }

    fun gitRoot(start: File, repos: File): File? {
        var cursor = if (start.isDirectory) start else start.parentFile
        val stop = repos.canonicalPath
        while (cursor != null) {
            if (File(cursor, ".git").exists()) return cursor
            val path = cursor.canonicalPath
            if (path == stop) return null
            if (!path.startsWith(stop + File.separator)) return null
            cursor = cursor.parentFile
        }
        return null
    }

    fun projectRoot(cwd: File, repos: File): File {
        gitRoot(cwd, repos)?.let { return it }
        val path = rel(cwd, repos)
        if (path.isEmpty()) error("cd into a repo first")
        val top = path.substringBefore('/')
        val dir = File(repos, top)
        if (!dir.isDirectory) error("cd into a repo first")
        return dir
    }

    fun walk(root: File, visit: (File) -> Unit) {
        val stack = ArrayDeque<File>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeFirst()
            val kids = dir.listFiles() ?: continue
            for (kid in kids.sortedBy { it.name.lowercase() }) {
                if (kid.isDirectory) {
                    if (kid.name !in SKIP_DIRS) stack.add(kid)
                } else {
                    visit(kid)
                }
            }
        }
    }
}
