package com.example.andvibe

import java.io.File

object Console {
    private const val HELP = """
AndVibe
  help                         this list
  clear                        clear the screen
  pwd    ls [path]    cd [path]
  cat <file>    open <file>
  git clone <https-url> [dir]
  git status    git pull
  compile                      syntax-check JavaScript
  test                         run *.test.js and test/
  run [file]                   preview HTML or run JS

Clone a public repo, edit it on the Vibe tab with your own API key,
then compile / test / run here.

JavaScript can use relative require("./file") and assert.equal.
npm, Python, Java, Kotlin, Gradle, Rust, and Go do not build on the phone.
HTML projects open in a preview.
"""

    fun run(line: String) {
        val parts = tokenize(line)
        if (parts.isEmpty()) return
        val args = parts.drop(1)
        when (parts[0]) {
            "help" -> AppState.log(HELP.trimIndent())
            "clear" -> AppState.clear()
            "pwd" -> AppState.log(RepoFiles.display(AppState.cwd, AppState.reposDir))
            "ls" -> ls(args)
            "cd" -> cd(args)
            "cat" -> cat(args)
            "open" -> open(args)
            "git" -> git(args)
            "compile" -> AppState.log(JsRunner.compile(AppState.projectRoot()))
            "test" -> AppState.log(JsRunner.test(AppState.projectRoot()))
            "run" -> runFile(args.firstOrNull())
            else -> AppState.log("unknown command: ${parts[0]} (try help)")
        }
    }

    fun tokenize(input: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (ch in input.trim()) {
            when {
                quote != null && ch == quote -> quote = null
                quote != null -> current.append(ch)
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> if (current.isNotEmpty()) {
                    out.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    private fun ls(args: List<String>) {
        val target = if (args.isEmpty()) {
            AppState.cwd
        } else {
            RepoFiles.resolve(AppState.cwd, AppState.reposDir, args[0])
        }
        if (!target.exists()) error("no such path")
        if (target.isFile) {
            AppState.log(target.name)
            return
        }
        val kids = target.listFiles()
            ?.filter { it.name != ".git" }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .orEmpty()
        if (kids.isEmpty()) AppState.log("(empty)")
        else kids.forEach { AppState.log(if (it.isDirectory) it.name + "/" else it.name) }
    }

    private fun cd(args: List<String>) {
        val dir = if (args.isEmpty()) {
            AppState.reposDir
        } else {
            RepoFiles.resolve(AppState.cwd, AppState.reposDir, args[0])
        }
        if (!dir.isDirectory) error("not a directory")
        AppState.cwd = dir
        UiBridge.filesChanged()
        AppState.log(RepoFiles.display(dir, AppState.reposDir))
    }

    private fun cat(args: List<String>) {
        if (args.isEmpty()) error("usage: cat <file>")
        val file = RepoFiles.resolve(AppState.cwd, AppState.reposDir, args[0])
        if (!file.isFile) error("not a file")
        if (file.length() > 64_000) error("file too large to print")
        if (RepoFiles.looksBinary(file)) error("binary file")
        AppState.log(file.readText())
    }

    private fun open(args: List<String>) {
        if (args.isEmpty()) error("usage: open <file>")
        val file = RepoFiles.resolve(AppState.cwd, AppState.reposDir, args[0])
        if (!file.isFile) error("not a file")
        UiBridge.open(file)
    }

    private fun git(args: List<String>) {
        if (args.isEmpty()) {
            AppState.log("git clone <url> [dir] | git status | git pull")
            return
        }
        when (args[0]) {
            "clone" -> {
                if (args.size < 2 || args.size > 3) error("usage: git clone <url> [dir]")
                val url = GitClient.normalizeGitUrl(args[1])
                val name = if (args.size == 3) GitClient.safeRepoName(args[2]) else GitClient.repoNameFromUrl(url)
                val dest = File(AppState.reposDir, name)
                GitClient.clone(url, dest, AppState::log)
                AppState.cwd = dest.canonicalFile
                UiBridge.filesChanged()
                AppState.log("now in ${RepoFiles.display(AppState.cwd, AppState.reposDir)}")
            }
            "status" -> AppState.log(GitClient.status(AppState.cwd, AppState.reposDir))
            "pull" -> AppState.log(GitClient.pull(AppState.cwd, AppState.reposDir))
            else -> AppState.log("unknown git command. try: clone, status, pull")
        }
    }

    private fun runFile(arg: String?) {
        if (arg != null) {
            val file = RepoFiles.resolve(AppState.cwd, AppState.reposDir, arg)
            if (!file.isFile) {
                AppState.log("no such file")
                return
            }
            val ext = file.extension.lowercase()
            when {
                ext == "html" || ext == "htm" -> UiBridge.preview(file)
                ext in RepoFiles.JS_EXT -> {
                    val root = runCatching { AppState.projectRoot() }.getOrDefault(file.parentFile ?: AppState.reposDir)
                    AppState.log(JsRunner.runJs(root, file))
                }
                else -> AppState.log("can't run ${file.name}. use an .html or .js file")
            }
            return
        }
        val root = AppState.projectRoot()
        when (val result = JsRunner.runAuto(root, AppState.cwd)) {
            is JsRunner.Result.Text -> AppState.log(result.text)
            is JsRunner.Result.Html -> UiBridge.preview(result.file)
        }
    }
}

fun AppState.projectRoot(): File = RepoFiles.projectRoot(cwd, reposDir)
