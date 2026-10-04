package com.example.andvibe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AgentContext(
    val root: File,
    val repos: File,
    val app: Context,
    val buildUrl: String,
    val buildToken: String
) {
    val changed = linkedSetOf<File>()
}

class ToolSpec(val name: String, val description: String, val schema: JSONObject)

object AgentTools {
    private const val MAX_READ_LINES = 600
    private const val MAX_GREP_HITS = 200
    private const val MAX_LIST = 400
    private const val MAX_WRITE = 500_000

    val specs: List<ToolSpec> = listOf(
        ToolSpec(
            "list_dir",
            "List files and folders under a path in the repo. Folders end with /. Skips .git, build, node_modules, and .gradle.",
            schema(
                "path" to prop("string", "Path relative to the repo root. Use . for the root."),
                "depth" to prop("integer", "How many folder levels to show, 1 to 4. Default 1.")
            )
        ),
        ToolSpec(
            "read_file",
            "Read a text file with line numbers. Reads at most $MAX_READ_LINES lines per call; pass start_line and end_line for more.",
            schema(
                "path" to prop("string", "Path relative to the repo root."),
                "start_line" to prop("integer", "First line, starting at 1."),
                "end_line" to prop("integer", "Last line, inclusive."),
                required = listOf("path")
            )
        ),
        ToolSpec(
            "grep",
            "Search file contents with a regular expression. Returns path:line: text for each match, up to $MAX_GREP_HITS matches.",
            schema(
                "pattern" to prop("string", "Java regular expression."),
                "path" to prop("string", "Folder or file to search, relative to the repo root. Default is the whole repo."),
                "glob" to prop("string", "Only search files matching this glob, like *.kt or src/**/*.java."),
                "ignore_case" to prop("boolean", "Case-insensitive match."),
                required = listOf("pattern")
            )
        ),
        ToolSpec(
            "edit_file",
            "Replace an exact piece of text in an existing file. old_string must match the file exactly, including whitespace, and must appear once unless replace_all is true. Include a few lines of surrounding context so the match is unique.",
            schema(
                "path" to prop("string", "Path relative to the repo root."),
                "old_string" to prop("string", "Exact text to replace."),
                "new_string" to prop("string", "Replacement text."),
                "replace_all" to prop("boolean", "Replace every occurrence."),
                required = listOf("path", "old_string", "new_string")
            )
        ),
        ToolSpec(
            "write_file",
            "Create a file, or replace a whole file. Prefer edit_file for changes to an existing file.",
            schema(
                "path" to prop("string", "Path relative to the repo root."),
                "content" to prop("string", "Complete file contents."),
                required = listOf("path", "content")
            )
        ),
        ToolSpec(
            "delete_file",
            "Delete one file in the repo.",
            schema(
                "path" to prop("string", "Path relative to the repo root."),
                required = listOf("path")
            )
        ),
        ToolSpec(
            "git_status",
            "Show the branch and the changed files in the working tree.",
            schema()
        ),
        ToolSpec(
            "git_diff",
            "Show the unstaged diff for one file, or for the whole repo.",
            schema("path" to prop("string", "Optional path relative to the repo root."))
        ),
        ToolSpec(
            "run_js_tests",
            "Syntax-check every JavaScript file and run *.test.js, *.spec.js, and files under test/ on the phone. Only JavaScript runs on the phone.",
            schema()
        ),
        ToolSpec(
            "cloud_build",
            "Compile a Gradle project on Cloud Run with assembleDebug and return the end of the build log. Takes several minutes. Only works when the repo has gradlew.",
            schema()
        )
    )

    fun run(name: String, args: JSONObject, ctx: AgentContext): String {
        return when (name) {
            "list_dir" -> listDir(ctx, args.optString("path", "."), args.optInt("depth", 1))
            "read_file" -> readFile(ctx, args.optString("path"), args.optInt("start_line", 1), args.optInt("end_line", 0))
            "grep" -> grep(
                ctx,
                args.optString("pattern"),
                args.optString("path", "."),
                args.optString("glob"),
                args.optBoolean("ignore_case", false)
            )
            "edit_file" -> editFile(
                ctx,
                args.optString("path"),
                args.optString("old_string"),
                args.optString("new_string"),
                args.optBoolean("replace_all", false)
            )
            "write_file" -> writeFile(ctx, args.optString("path"), args.optString("content"))
            "delete_file" -> deleteFile(ctx, args.optString("path"))
            "git_status" -> GitOps.status(ctx.root, ctx.repos)
            "git_diff" -> GitOps.diff(ctx.root, ctx.repos, args.optString("path").ifBlank { null }, false)
            "run_js_tests" -> JsRunner.compile(ctx.root) + "\n\n" + JsRunner.test(ctx.root)
            "cloud_build" -> cloudBuild(ctx)
            else -> error("unknown tool $name")
        }
    }

    fun label(name: String, args: JSONObject): String {
        val path = args.optString("path")
        return when (name) {
            "list_dir" -> "list ${path.ifBlank { "." }}"
            "read_file" -> {
                val start = args.optInt("start_line", 0)
                val end = args.optInt("end_line", 0)
                if (start > 0 || end > 0) "read $path:${start.coerceAtLeast(1)}-${if (end > 0) end else "end"}" else "read $path"
            }
            "grep" -> "grep \"${args.optString("pattern").take(60)}\"" +
                args.optString("glob").let { if (it.isBlank()) "" else " in $it" }
            "edit_file" -> "edit $path"
            "write_file" -> "write $path"
            "delete_file" -> "delete $path"
            "git_status" -> "git status"
            "git_diff" -> "git diff ${path}".trimEnd()
            "run_js_tests" -> "run JavaScript tests"
            "cloud_build" -> "build on Cloud Run"
            else -> name
        }
    }

    private fun listDir(ctx: AgentContext, raw: String, depthRaw: Int): String {
        val dir = target(ctx.root, raw)
        if (!dir.isDirectory) error("not a folder: $raw")
        val depth = depthRaw.coerceIn(1, 4)
        val lines = mutableListOf<String>()
        fun walk(folder: File, level: Int) {
            val kids = folder.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
            for (kid in kids) {
                if (lines.size >= MAX_LIST) return
                val rel = RepoFiles.rel(kid, ctx.root)
                if (kid.isDirectory) {
                    if (kid.name in RepoFiles.SKIP_DIRS) continue
                    lines.add("$rel/")
                    if (level < depth) walk(kid, level + 1)
                } else {
                    lines.add("$rel  ${size(kid.length())}")
                }
            }
        }
        walk(dir, 1)
        if (lines.isEmpty()) return "(empty)"
        if (lines.size >= MAX_LIST) lines.add("… stopped at $MAX_LIST entries")
        return lines.joinToString("\n")
    }

    private fun readFile(ctx: AgentContext, raw: String, startRaw: Int, endRaw: Int): String {
        val file = target(ctx.root, raw)
        if (!file.isFile) error("no such file: $raw")
        if (file.length() > 4_000_000) error("file is ${size(file.length())}, too large to read")
        if (RepoFiles.looksBinary(file)) error("binary file: $raw")
        val lines = file.readLines()
        if (lines.isEmpty()) return "(empty file)"
        val start = startRaw.coerceAtLeast(1)
        if (start > lines.size) error("file has ${lines.size} lines")
        val wanted = if (endRaw <= 0) lines.size else endRaw.coerceAtMost(lines.size)
        val end = minOf(wanted, start + MAX_READ_LINES - 1)
        return buildString {
            for (i in start..end) {
                append(i.toString().padStart(6)).append('|').append(lines[i - 1].take(2000)).append('\n')
            }
            if (end < lines.size) append("… ${lines.size - end} more lines. Next: start_line=${end + 1}\n")
        }.trimEnd()
    }

    private fun grep(ctx: AgentContext, pattern: String, raw: String, glob: String, ignoreCase: Boolean): String {
        if (pattern.isEmpty()) error("pattern is empty")
        val regex = try {
            if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)
        } catch (e: Exception) {
            error("bad regex: ${e.message}")
        }
        val matcher = if (glob.isBlank()) null else globRegex(glob.trim())
        val start = target(ctx.root, raw.ifBlank { "." })
        val hits = mutableListOf<String>()
        var files = 0
        fun scan(file: File) {
            if (hits.size >= MAX_GREP_HITS) return
            if (file.length() > 1_500_000 || RepoFiles.looksBinary(file)) return
            val rel = RepoFiles.rel(file, ctx.root)
            if (matcher != null) {
                val subject = if (glob.contains('/')) rel else file.name
                if (!matcher.matches(subject)) return
            }
            files++
            var number = 0
            file.bufferedReader().useLines { seq ->
                for (line in seq) {
                    number++
                    if (regex.containsMatchIn(line)) {
                        hits.add("$rel:$number: ${line.trim().take(300)}")
                        if (hits.size >= MAX_GREP_HITS) break
                    }
                }
            }
        }
        if (start.isFile) scan(start) else RepoFiles.walk(start) { scan(it) }
        if (hits.isEmpty()) return "no matches in $files files"
        val more = if (hits.size >= MAX_GREP_HITS) "\n… stopped at $MAX_GREP_HITS matches. Narrow the pattern or path." else ""
        return hits.joinToString("\n") + more
    }

    private fun editFile(ctx: AgentContext, raw: String, old: String, new: String, all: Boolean): String {
        val file = writable(ctx.root, raw)
        if (!file.isFile) error("no such file: $raw. Use write_file to create it.")
        if (old.isEmpty()) error("old_string is empty")
        if (old == new) error("old_string and new_string are the same")
        val text = file.readText()
        var find = old
        var replace = new
        if (!text.contains(find) && text.contains("\r\n") && !old.contains("\r\n")) {
            find = old.replace("\n", "\r\n")
            replace = new.replace("\n", "\r\n")
        }
        val count = text.split(find).size - 1
        if (count == 0) error("old_string was not found in $raw. Read the file again and copy the text exactly.")
        if (count > 1 && !all) error("old_string appears $count times in $raw. Add more context or set replace_all.")
        val updated = if (all) text.replace(find, replace) else text.replaceFirst(find, replace)
        file.writeText(updated)
        ctx.changed.add(file)
        val removed = find.lines().size
        val added = replace.lines().size
        val times = if (all && count > 1) " ($count places)" else ""
        return "edited $raw$times: replaced $removed line(s) with $added"
    }

    private fun writeFile(ctx: AgentContext, raw: String, content: String): String {
        if (content.length > MAX_WRITE) error("content is over 500KB")
        val file = writable(ctx.root, raw)
        if (file.isDirectory) error("$raw is a folder")
        val existed = file.isFile
        file.parentFile?.mkdirs()
        file.writeText(content)
        ctx.changed.add(file)
        val lines = content.lines().size
        return if (existed) "replaced $raw ($lines lines)" else "created $raw ($lines lines)"
    }

    private fun deleteFile(ctx: AgentContext, raw: String): String {
        val file = writable(ctx.root, raw)
        if (!file.isFile) error("no such file: $raw")
        if (!file.delete()) error("could not delete $raw")
        ctx.changed.add(file)
        return "deleted $raw"
    }

    private fun cloudBuild(ctx: AgentContext): String {
        if (!File(ctx.root, "gradlew").isFile) error("this repo has no gradlew, so Cloud Run cannot build it")
        if (ctx.buildUrl.isBlank() || ctx.buildToken.isBlank()) {
            error("the Cloud Run URL or token is not set. The user sets them on Console → Variables.")
        }
        if (AppState.buildBusy) error("a build is already running")
        AppState.buildBusy = true
        AppState.clearBuild()
        val log = StringBuilder()
        val note: (String) -> Unit = { line ->
            AppState.buildLog(line)
            synchronized(log) {
                log.append(line).append('\n')
                if (log.length > 200_000) log.delete(0, log.length - 120_000)
            }
        }
        try {
            note("Agent build. Sending ${ctx.root.name} to Cloud Run.")
            val apk = CloudBuild.build(ctx.app, ctx.root, ctx.buildUrl, ctx.buildToken, note)
            AppState.lastApk = apk.absolutePath
            note("APK")
            note(apk.absolutePath)
            return "BUILD SUCCESSFUL\nAPK: ${apk.name}\n\n" + tail(log.toString(), 3_000)
        } catch (t: Throwable) {
            val message = t.message ?: t.javaClass.simpleName
            note("build failed: $message")
            return "BUILD FAILED: $message\n\n" + tail(log.toString(), 14_000)
        } finally {
            AppState.buildBusy = false
            UiBridge.buildUpdate()
        }
    }

    private fun target(root: File, raw: String): File {
        val rel = raw.trim().replace('\\', '/').removePrefix("./").trim('/')
        if (rel.isEmpty() || rel == ".") return root.canonicalFile
        val parts = rel.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) error("paths cannot use ..")
        val file = File(root, parts.joinToString(File.separator)).canonicalFile
        RepoFiles.ensureInside(root, file)
        return file
    }

    private fun writable(root: File, raw: String): File {
        val file = target(root, raw)
        if (file == root.canonicalFile) error("bad path: $raw")
        val rel = RepoFiles.rel(file, root)
        if (rel.split('/').any { it == ".git" }) error("the agent cannot change .git")
        return file
    }

    private fun globRegex(glob: String): Regex {
        val out = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' && glob.getOrNull(i + 1) == '*' -> {
                    out.append(".*")
                    i++
                    if (glob.getOrNull(i + 1) == '/') i++
                }
                c == '*' -> out.append("[^/]*")
                c == '?' -> out.append("[^/]")
                c == '{' -> out.append("(?:")
                c == '}' -> out.append(")")
                c == ',' -> out.append("|")
                c in ".()+|^$[]\\" -> out.append('\\').append(c)
                else -> out.append(c)
            }
            i++
        }
        out.append("$")
        return Regex(out.toString())
    }

    private fun tail(text: String, max: Int): String {
        return if (text.length <= max) text else "…\n" + text.takeLast(max)
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    private fun prop(type: String, description: String): JSONObject {
        return JSONObject().put("type", type).put("description", description)
    }

    private fun schema(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
        val properties = JSONObject()
        props.forEach { (name, value) -> properties.put(name, value) }
        val out = JSONObject().put("type", "object").put("properties", properties)
        if (required.isNotEmpty()) out.put("required", JSONArray(required))
        return out
    }
}
