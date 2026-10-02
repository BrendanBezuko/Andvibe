package com.example.andvibe

import java.io.File

object Console {
    private const val HELP = """
AndVibe
  help                         this list
  clear                        clear the screen
  pwd    ls [path]    cd [path]
  cat <file>    open <file>
  projects                     folders already in AndVibe
  git clone <https-url> [dir]
  git status | add <file> | add .
  git commit -m "message"
  git push | pull | fetch | log
  git diff [file] | branch
  git checkout <branch>
  git checkout -b <name>
  git restore <file> | init
  git remote | git remote add origin <url>
  compile                      syntax-check JavaScript
  test                         run *.test.js and test/
  run [file]                   preview HTML or run JS

Find on this tab searches GitHub, GitLab, and Codeberg. Tap a result and Files opens as the download starts.
Open an older folder from Files, or clone a public repo.
Vibe writes files and leaves them uncommitted. Stage and commit them on the Git tab.
The Git tab stages, commits, and pushes. Edit on Vibe, then Build.
A project with gradlew uploads to Cloud Run, and the build log streams here. Revise on the Build tab edits the project on this phone. The API key stays on the device. Other projects pack on the phone.

JavaScript can use relative require("./file") and assert.equal.
npm, Python, Rust, and Go do not build here.
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
            "projects" -> projects()
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
        if (dir.canonicalFile != AppState.reposDir.canonicalFile) {
            ProjectStore.remember(AppState.appContext, dir)
        }
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

    private fun projects() {
        val dirs = AppState.reposDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        if (dirs.isEmpty()) AppState.log("no projects. git clone, or Open a folder on the Files tab")
        else dirs.forEach { AppState.log(it.name + "/") }
    }

    private fun git(args: List<String>) {
        if (args.isEmpty()) {
            AppState.log("git status, add, commit -m, push, pull, fetch, log, diff, branch, checkout, init")
            return
        }
        val cwd = AppState.cwd
        val repos = AppState.reposDir
        when (args[0]) {
            "clone" -> {
                if (args.size < 2 || args.size > 3) error("usage: git clone <url> [dir]")
                val url = GitClient.normalizeGitUrl(args[1])
                val name = if (args.size == 3) GitClient.safeRepoName(args[2]) else GitClient.repoNameFromUrl(url)
                val dest = File(repos, name)
                GitClient.clone(url, dest, AppState::log)
                AppState.cwd = dest.canonicalFile
                ProjectStore.remember(AppState.appContext, AppState.cwd)
                UiBridge.filesChanged()
                AppState.log("now in ${RepoFiles.display(AppState.cwd, repos)}")
            }
            "status" -> AppState.log(GitOps.status(cwd, repos))
            "add" -> {
                if (args.size != 2) error("usage: git add <file> | git add .")
                AppState.log(GitOps.stage(cwd, repos, args[1]))
                UiBridge.filesChanged()
            }
            "reset" -> {
                if (args.size != 2) error("usage: git reset <file>")
                AppState.log(GitOps.unstage(cwd, repos, args[1]))
            }
            "restore" -> {
                if (args.size != 2) error("usage: git restore <file>")
                AppState.log(GitOps.discard(cwd, repos, args[1]))
                UiBridge.filesChanged()
            }
            "commit" -> {
                val message = when {
                    args.size >= 3 && args[1] == "-m" -> args.drop(2).joinToString(" ")
                    args.size >= 2 -> args.drop(1).joinToString(" ")
                    else -> error("usage: git commit -m \"message\"")
                }
                AppState.log(GitOps.commit(cwd, repos, message))
            }
            "push" -> AppState.log(GitOps.push(cwd, repos))
            "pull" -> AppState.log(GitOps.pull(cwd, repos))
            "fetch" -> AppState.log(GitOps.fetch(cwd, repos))
            "log" -> AppState.log(GitOps.history(cwd, repos))
            "diff" -> AppState.log(GitOps.diff(cwd, repos, args.getOrNull(1), staged = false))
            "branch" -> AppState.log(GitOps.branchReport(cwd, repos))
            "checkout", "switch" -> {
                when {
                    args.size == 3 && args[1] == "-b" -> AppState.log(GitOps.createBranch(cwd, repos, args[2]))
                    args.size == 2 -> AppState.log(GitOps.checkout(cwd, repos, args[1]))
                    else -> error("usage: git checkout <branch> | git checkout -b <name>")
                }
                UiBridge.filesChanged()
            }
            "init" -> AppState.log(GitOps.init(AppState.projectRoot()))
            "remote" -> {
                if (args.size == 1) AppState.log(GitOps.remoteSummary(cwd, repos))
                else if (args.size == 4 && args[1] == "add") AppState.log(GitOps.addRemote(cwd, repos, args[2], args[3]))
                else error("usage: git remote | git remote add <name> <url>")
            }
            else -> AppState.log("unknown git command. try help")
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
