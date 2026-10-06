package com.example.andvibe.features.console

import com.example.andvibe.ProjectSession
import com.example.andvibe.Tab
import com.example.andvibe.WorkspaceStore
import com.example.andvibe.core.GitClient
import com.example.andvibe.core.GitOps
import com.example.andvibe.core.JsRunner
import com.example.andvibe.core.RepoFiles
import com.example.andvibe.features.FeatureEffects
import com.example.andvibe.tasks.AppDispatchers
import com.example.andvibe.tasks.Res
import com.example.andvibe.tasks.TaskRunner
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ConsoleContext(
    val session: ProjectSession,
    val log: ConsoleLog,
    val appContext: android.content.Context,
    val onOpen: (File) -> Unit,
    val onPreview: (File) -> Unit,
    val onFilesChanged: () -> Unit,
    val onGitRefresh: () -> Unit = {},
    val inWorkspace: (File) -> File,
    val projectRoot: () -> File,
    val activeRepos: () -> List<File>,
    val includeProject: (String) -> Unit,
    val rememberProject: (File) -> Unit,
)

class ConsoleFeature(
    private val tasks: TaskRunner,
    private val dispatchers: AppDispatchers,
    val log: ConsoleLog,
    private val context: () -> ConsoleContext,
    private val onLogChanged: () -> Unit,
) {
    data class State(
        val logText: String = "",
        val running: Boolean = false,
    )

    sealed interface Effect {
        data class OpenFile(val file: File) : Effect
        data class Preview(val file: File) : Effect
        data object RefreshFiles : Effect
    }

    private val _state = MutableStateFlow(State(logText = log.text()))
    val state: StateFlow<State> = _state.asStateFlow()
    private val effects = FeatureEffects<Effect>()
    val effectsFlow = effects.events

    init {
        // Keep state.logText in sync when log mutates from elsewhere.
    }

    fun refreshLog() {
        _state.update { it.copy(logText = log.text(), running = tasks.holds(Res.CONSOLE)) }
    }

    fun clear() {
        log.clear()
        refreshLog()
        onLogChanged()
    }

    fun append(line: String) {
        log.append(line)
        refreshLog()
        onLogChanged()
    }

    fun submit(line: String) {
        if (tasks.holds(Res.CONSOLE)) return
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        append("$ $trimmed")
        val parts = ConsoleCommands.tokenize(trimmed)
        val slow = parts.getOrNull(0) == "git" && parts.getOrNull(1) in setOf("clone", "push", "pull", "fetch")
        val label = if (slow) parts.take(2).joinToString(" ") else "Running command"
        _state.update { it.copy(running = true) }
        tasks.launch(label, Tab.CONSOLE, setOf(Res.CONSOLE), dispatchers.repo, track = slow) {
            try {
                ConsoleCommands.run(trimmed, context())
                refreshLog()
                onLogChanged()
                if (slow) TaskRunner.Done("$label finished", trimmed) else null
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                append("error: $msg")
                if (slow) TaskRunner.Done("$label failed", msg) else null
            } finally {
                _state.update { it.copy(running = false, logText = log.text()) }
            }
        }
    }
}

/** Pure command dispatch — no global app state (DESIGN.md Phase 4 Console). */
object ConsoleCommands {
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

    fun run(line: String, ctx: ConsoleContext) {
        val parts = tokenize(line)
        if (parts.isEmpty()) return
        val args = parts.drop(1)
        when (parts[0]) {
            "help" -> ctx.log.append(HELP.trimIndent())
            "clear" -> ctx.log.clear()
            "pwd" -> ctx.log.append(RepoFiles.display(ctx.session.cwd, ctx.session.reposDir))
            "ls" -> ls(args, ctx)
            "cd" -> cd(args, ctx)
            "cat" -> cat(args, ctx)
            "open" -> open(args, ctx)
            "projects" -> projects(ctx)
            "git" -> git(args, ctx)
            "compile" -> ctx.log.append(JsRunner.compile(ctx.projectRoot()))
            "test" -> ctx.log.append(JsRunner.test(ctx.projectRoot()))
            "run" -> runFile(args.firstOrNull(), ctx)
            else -> ctx.log.append("unknown command: ${parts[0]} (try help)")
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

    private fun ls(args: List<String>, ctx: ConsoleContext) {
        val target = if (args.isEmpty()) ctx.session.cwd else resolve(args[0], ctx)
        if (!target.exists()) error("no such path")
        if (target.isFile) {
            ctx.log.append(target.name)
            return
        }
        val kids = target.listFiles()
            ?.filter { it.name != ".git" && WorkspaceStore.contains(it) }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .orEmpty()
        if (kids.isEmpty()) ctx.log.append("(empty)")
        else kids.forEach { ctx.log.append(if (it.isDirectory) it.name + "/" else it.name) }
    }

    private fun cd(args: List<String>, ctx: ConsoleContext) {
        val dir = if (args.isEmpty()) ctx.session.reposDir else resolve(args[0], ctx)
        if (!dir.isDirectory) error("not a directory")
        ctx.session.cwd = dir
        if (dir.canonicalFile != ctx.session.reposDir.canonicalFile) {
            ctx.rememberProject(dir)
        }
        ctx.onFilesChanged()
        ctx.log.append(RepoFiles.display(dir, ctx.session.reposDir))
    }

    private fun cat(args: List<String>, ctx: ConsoleContext) {
        if (args.isEmpty()) error("usage: cat <file>")
        val file = resolve(args[0], ctx)
        if (!file.isFile) error("not a file")
        if (file.length() > 64_000) error("file too large to print")
        if (RepoFiles.looksBinary(file)) error("binary file")
        ctx.log.append(file.readText())
    }

    private fun open(args: List<String>, ctx: ConsoleContext) {
        if (args.isEmpty()) error("usage: open <file>")
        val file = resolve(args[0], ctx)
        if (!file.isFile) error("not a file")
        ctx.onOpen(file)
    }

    private fun projects(ctx: ConsoleContext) {
        val dirs = ctx.activeRepos()
        if (dirs.isEmpty()) {
            ctx.log.append("no repos in this workspace. git clone, or pick downloaded repos on the Workspace page")
        } else {
            dirs.forEach { ctx.log.append(it.name + "/") }
        }
    }

    private fun resolve(raw: String, ctx: ConsoleContext): File =
        ctx.inWorkspace(RepoFiles.resolve(ctx.session.cwd, ctx.session.reposDir, raw))

    private fun git(args: List<String>, ctx: ConsoleContext) {
        if (args.isEmpty()) {
            ctx.log.append("git status, add, commit -m, push, pull, fetch, log, diff, branch, checkout, init")
            return
        }
        val cwd = ctx.session.cwd
        val repos = ctx.session.reposDir
        var refreshGit = false
        when (args[0]) {
            "clone" -> {
                if (args.size < 2 || args.size > 3) error("usage: git clone <url> [dir]")
                val url = GitClient.normalizeGitUrl(args[1])
                val name = if (args.size == 3) GitClient.safeRepoName(args[2]) else GitClient.repoNameFromUrl(url)
                val dest = File(repos, name)
                GitClient.clone(url, dest) { ctx.log.append(it) }
                ctx.includeProject(dest.name)
                ctx.session.cwd = dest.canonicalFile
                ctx.rememberProject(ctx.session.cwd)
                ctx.onFilesChanged()
                refreshGit = true
                ctx.log.append("now in ${RepoFiles.display(ctx.session.cwd, repos)}")
            }
            "status" -> ctx.log.append(GitOps.status(cwd, repos))
            "add" -> {
                if (args.size != 2) error("usage: git add <file> | git add .")
                ctx.log.append(GitOps.stage(cwd, repos, args[1]))
                ctx.onFilesChanged()
                refreshGit = true
            }
            "reset" -> {
                if (args.size != 2) error("usage: git reset <file>")
                ctx.log.append(GitOps.unstage(cwd, repos, args[1]))
                refreshGit = true
            }
            "restore" -> {
                if (args.size != 2) error("usage: git restore <file>")
                ctx.log.append(GitOps.discard(cwd, repos, args[1]))
                ctx.onFilesChanged()
                refreshGit = true
            }
            "commit" -> {
                val message = when {
                    args.size >= 3 && args[1] == "-m" -> args.drop(2).joinToString(" ")
                    args.size >= 2 -> args.drop(1).joinToString(" ")
                    else -> error("usage: git commit -m \"message\"")
                }
                ctx.log.append(GitOps.commit(cwd, repos, message))
                refreshGit = true
            }
            "push" -> {
                ctx.log.append(GitOps.push(cwd, repos))
                refreshGit = true
            }
            "pull" -> {
                ctx.log.append(GitOps.pull(cwd, repos))
                refreshGit = true
            }
            "fetch" -> {
                ctx.log.append(GitOps.fetch(cwd, repos))
                refreshGit = true
            }
            "log" -> ctx.log.append(GitOps.history(cwd, repos))
            "diff" -> ctx.log.append(GitOps.diff(cwd, repos, args.getOrNull(1), staged = false))
            "branch" -> ctx.log.append(GitOps.branchReport(cwd, repos))
            "checkout", "switch" -> {
                when {
                    args.size == 3 && args[1] == "-b" -> ctx.log.append(GitOps.createBranch(cwd, repos, args[2]))
                    args.size == 2 -> ctx.log.append(GitOps.checkout(cwd, repos, args[1]))
                    else -> error("usage: git checkout <branch> | git checkout -b <name>")
                }
                ctx.onFilesChanged()
                refreshGit = true
            }
            "init" -> {
                ctx.log.append(GitOps.init(ctx.projectRoot()))
                refreshGit = true
            }
            "remote" -> {
                if (args.size == 1) ctx.log.append(GitOps.remoteSummary(cwd, repos))
                else if (args.size == 4 && args[1] == "add") {
                    ctx.log.append(GitOps.addRemote(cwd, repos, args[2], args[3]))
                } else {
                    error("usage: git remote | git remote add <name> <url>")
                }
            }
            else -> ctx.log.append("unknown git command. try help")
        }
        if (refreshGit) ctx.onGitRefresh()
    }

    private fun runFile(arg: String?, ctx: ConsoleContext) {
        if (arg != null) {
            val file = resolve(arg, ctx)
            if (!file.isFile) {
                ctx.log.append("no such file")
                return
            }
            val ext = file.extension.lowercase()
            when {
                ext == "html" || ext == "htm" -> ctx.onPreview(file)
                ext in RepoFiles.JS_EXT -> {
                    val root = runCatching { ctx.projectRoot() }.getOrDefault(file.parentFile ?: ctx.session.reposDir)
                    ctx.log.append(JsRunner.runJs(root, file))
                }
                else -> ctx.log.append("can't run ${file.name}. use an .html or .js file")
            }
            return
        }
        val root = ctx.projectRoot()
        when (val result = JsRunner.runAuto(root, ctx.session.cwd)) {
            is JsRunner.Result.Text -> ctx.log.append(result.text)
            is JsRunner.Result.Html -> ctx.onPreview(result.file)
        }
    }
}
