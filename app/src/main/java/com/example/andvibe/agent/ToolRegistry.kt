package com.example.andvibe.agent

import com.example.andvibe.core.GitClient
import com.example.andvibe.core.GitOps
import com.example.andvibe.core.JsRunner
import com.example.andvibe.core.RepoFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Named tools with individual execute bodies (DESIGN.md §3.7).
 * Replaces the monolithic AgentTools dispatch. No global app state or UI listener bus.
 */
class ToolRegistry(
    private val cloudBuild: (root: File) -> String,
    private val includeProject: (String) -> Unit,
) {
    private val tools: List<RegisteredTool> = listOf(
        tool("list_dir", plan = true,
            "List files and folders under a path in the repo. Folders end with /. Skips .git, build, node_modules, and .gradle.",
            schema(
                "path" to prop("string", "Path relative to the repo root. Use . for the root."),
                "depth" to prop("integer", "How many folder levels to show, 1 to 4. Default 1.")
            )
        ) { ctx, args -> listDir(ctx, args.optString("path", "."), args.optInt("depth", 1)) },
        tool("read_file", plan = true,
            "Read a text file with line numbers. Reads at most $MAX_READ_LINES lines per call; pass start_line and end_line for more.",
            schema(
                "path" to prop("string", "Path relative to the repo root."),
                "start_line" to prop("integer", "First line, starting at 1."),
                "end_line" to prop("integer", "Last line, inclusive."),
                required = listOf("path")
            )
        ) { ctx, args ->
            readFile(ctx, args.optString("path"), args.optInt("start_line", 1), args.optInt("end_line", 0))
        },
        tool("grep", plan = true,
            "Search file contents with a regular expression. Returns path:line: text for each match, up to $MAX_GREP_HITS matches.",
            schema(
                "pattern" to prop("string", "Java regular expression."),
                "path" to prop("string", "Folder or file to search, relative to the repo root. Default is the whole repo."),
                "glob" to prop("string", "Only search files matching this glob, like *.kt or src/**/*.java."),
                "ignore_case" to prop("boolean", "Case-insensitive match."),
                required = listOf("pattern")
            )
        ) { ctx, args ->
            grep(
                ctx,
                args.optString("pattern"),
                args.optString("path", "."),
                args.optString("glob"),
                args.optBoolean("ignore_case", false)
            )
        },
        tool("edit_file", plan = false,
            "Replace an exact piece of text in an existing file. old_string must match the file exactly, including whitespace, and must appear once unless replace_all is true. Include a few lines of surrounding context so the match is unique.",
            schema(
                "path" to prop("string", "Path relative to the repo root."),
                "old_string" to prop("string", "Exact text to replace."),
                "new_string" to prop("string", "Replacement text."),
                "replace_all" to prop("boolean", "Replace every occurrence."),
                required = listOf("path", "old_string", "new_string")
            )
        ) { ctx, args ->
            editFile(
                ctx,
                args.optString("path"),
                args.optString("old_string"),
                args.optString("new_string"),
                args.optBoolean("replace_all", false)
            )
        },
        tool("write_file", plan = false,
            "Create a file, or replace a whole file. Prefer edit_file for changes to an existing file.",
            schema(
                "path" to prop("string", "Path relative to the repo root."),
                "content" to prop("string", "Complete file contents."),
                required = listOf("path", "content")
            )
        ) { ctx, args -> writeFile(ctx, args.optString("path"), args.optString("content")) },
        tool("delete_file", plan = false,
            "Delete one file in the repo.",
            schema(
                "path" to prop("string", "Path relative to the repo root."),
                required = listOf("path")
            )
        ) { ctx, args -> deleteFile(ctx, args.optString("path")) },
        tool("git_status", plan = true,
            "Show the branch and the changed files in the working tree.",
            schema()
        ) { ctx, _ -> GitOps.status(ctx.repo, ctx.repos) },
        tool("git_diff", plan = true,
            "Show the unstaged diff for one file, or for the whole repo.",
            schema("path" to prop("string", "Optional path relative to the repo root."))
        ) { ctx, args ->
            GitOps.diff(ctx.repo, ctx.repos, args.optString("path").ifBlank { null }, false)
        },
        tool("run_js_tests", plan = false,
            "Syntax-check every JavaScript file and run *.test.js, *.spec.js, and files under test/ on the phone. Only JavaScript runs on the phone.",
            schema()
        ) { ctx, _ -> JsRunner.compile(ctx.repo) + "\n\n" + JsRunner.test(ctx.repo) },
        tool("cloud_build", plan = false,
            "Compile a Gradle project on Cloud Run with assembleDebug and return the end of the build log. Takes several minutes. Only works when the repo has gradlew.",
            schema()
        ) { ctx, _ -> cloudBuild(ctx.repo) },
        tool("create_project", plan = false,
            "Create a new empty project folder with its own git repo, add it to the workspace, and switch to it. Every later tool call works in the new project. Only call this when the user explicitly asks for a new project, app, or repo. Never use it to start over on the current repo.",
            schema(
                "name" to prop("string", "Folder name. Letters, digits, dot, dash, and underscore."),
                required = listOf("name")
            )
        ) { ctx, args -> createProject(ctx, args.optString("name")) },
    )

    private val byName = tools.associateBy { it.spec.name }

    val specs: List<ToolSpec> = tools.map { it.spec }
    val planSpecs: List<ToolSpec> = tools.filter { it.plan }.map { it.spec }

    fun run(name: String, args: JSONObject, ctx: AgentContext): String {
        val tool = byName[name] ?: error("unknown tool $name")
        return tool.execute(ctx, args)
    }

    fun label(name: String, args: JSONObject): String {
        val path = args.optString("path")
        return when (name) {
            "list_dir" -> "list ${path.ifBlank { "." }}"
            "read_file" -> {
                val start = args.optInt("start_line", 0)
                val end = args.optInt("end_line", 0)
                if (start > 0 || end > 0) {
                    "read $path:${start.coerceAtLeast(1)}-${if (end > 0) end else "end"}"
                } else {
                    "read $path"
                }
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
            "create_project" -> "create project ${args.optString("name")}"
            else -> name
        }
    }

    private fun createProject(ctx: AgentContext, raw: String): String {
        val name = runCatching { GitClient.safeRepoName(raw) }.getOrElse { error("bad project name: $raw") }
        val dir = File(ctx.repos, name).canonicalFile
        RepoFiles.ensureInside(ctx.repos, dir)
        if (dir.exists() && !dir.list().isNullOrEmpty()) error("~/$name already exists. Pick another name.")
        if (!dir.mkdirs() && !dir.isDirectory) error("could not create ~/$name")
        val git = GitOps.init(dir)
        includeProject(name)
        ctx.root = dir
        ctx.onRootChanged(dir)
        return "created ~/$name ($git). It is now the repo for every later tool call; paths are relative to it."
    }

    private fun listDir(ctx: AgentContext, raw: String, depthRaw: Int): String {
        val dir = target(ctx.repo, raw)
        if (!dir.isDirectory) error("not a folder: $raw")
        val depth = depthRaw.coerceIn(1, 4)
        val lines = mutableListOf<String>()
        fun walk(folder: File, level: Int) {
            val kids = folder.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
            for (kid in kids) {
                if (lines.size >= MAX_LIST) return
                val rel = RepoFiles.rel(kid, ctx.repo)
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
        val file = target(ctx.repo, raw)
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
        val start = target(ctx.repo, raw.ifBlank { "." })
        val hits = mutableListOf<String>()
        var files = 0
        fun scan(file: File) {
            if (hits.size >= MAX_GREP_HITS) return
            if (file.length() > 1_500_000 || RepoFiles.looksBinary(file)) return
            val rel = RepoFiles.rel(file, ctx.repo)
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
        val more = if (hits.size >= MAX_GREP_HITS) {
            "\n… stopped at $MAX_GREP_HITS matches. Narrow the pattern or path."
        } else {
            ""
        }
        return hits.joinToString("\n") + more
    }

    private fun editFile(ctx: AgentContext, raw: String, old: String, new: String, all: Boolean): String {
        val file = writable(ctx.repo, raw)
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
        val file = writable(ctx.repo, raw)
        if (file.isDirectory) error("$raw is a folder")
        val existed = file.isFile
        file.parentFile?.mkdirs()
        file.writeText(content)
        ctx.changed.add(file)
        val lines = content.lines().size
        return if (existed) "replaced $raw ($lines lines)" else "created $raw ($lines lines)"
    }

    private fun deleteFile(ctx: AgentContext, raw: String): String {
        val file = writable(ctx.repo, raw)
        if (!file.isFile) error("no such file: $raw")
        if (!file.delete()) error("could not delete $raw")
        ctx.changed.add(file)
        return "deleted $raw"
    }

    companion object {
        private const val MAX_READ_LINES = 600
        private const val MAX_GREP_HITS = 200
        private const val MAX_LIST = 400
        private const val MAX_WRITE = 500_000

        /** Pure helpers exposed for JVM tests. */
        fun target(root: File, raw: String): File {
            val rel = raw.trim().replace('\\', '/').removePrefix("./").trim('/')
            if (rel.isEmpty() || rel == ".") return root.canonicalFile
            val parts = rel.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.any { it == ".." }) error("paths cannot use ..")
            val file = File(root, parts.joinToString(File.separator)).canonicalFile
            RepoFiles.ensureInside(root, file)
            return file
        }

        fun writable(root: File, raw: String): File {
            val file = target(root, raw)
            if (file == root.canonicalFile) error("bad path: $raw")
            val rel = RepoFiles.rel(file, root)
            if (rel.split('/').any { it == ".git" }) error("the agent cannot change .git")
            return file
        }

        fun globRegex(glob: String): Regex {
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

        private fun tool(
            name: String,
            plan: Boolean,
            description: String,
            schema: JSONObject,
            body: Tool,
        ): RegisteredTool = RegisteredTool(ToolSpec(name, description, schema), plan, body)
    }
}
