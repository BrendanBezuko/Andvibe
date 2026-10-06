package com.example.andvibe.core

import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object JsRunner {
    sealed class Result {
        data class Text(val text: String) : Result()
        data class Html(val file: File) : Result()
    }

    private const val MAX_JS = 256 * 1024
    private val prelude = """
        var console = { log: print, error: print, warn: print, info: print };
        assert.equal = function(a, b, msg) {
          assert(a === b, msg || (String(a) + " !== " + String(b)));
        };
        assert.strictEqual = assert.equal;
        assert.ok = function(v, msg) { assert(!!v, msg || "expected truthy"); };
        assert.deepEqual = function(a, b, msg) {
          assert(JSON.stringify(a) === JSON.stringify(b), msg || (JSON.stringify(a) + " !== " + JSON.stringify(b)));
        };
    """.trimIndent()

    fun compile(root: File): String {
        val found = jsFiles(root)
        if (found.first.isEmpty()) return "no JavaScript files\n${detect(root)}"
        val fails = mutableListOf<String>()
        JsRuntime.use { cx ->
            for (file in found.first) {
                JsRuntime.resetSteps()
                val rel = RepoFiles.rel(file, root)
                try {
                    cx.compileString(prepare(file), rel, 1, null)
                } catch (e: RhinoException) {
                    fails.add("FAIL $rel:${lineOf(e)} ${e.message ?: e}")
                } catch (e: Exception) {
                    fails.add("FAIL $rel ${e.message ?: e}")
                }
            }
        }
        val capped = if (found.second) "\nstopped at ${found.first.size} files" else ""
        val ok = found.first.size - fails.size
        return if (fails.isEmpty()) "${found.first.size} files ok$capped"
        else fails.joinToString("\n") + "\n$ok ok, ${fails.size} failed$capped"
    }

    fun test(root: File): String {
        val tests = findTests(root)
        if (tests.isEmpty()) return "no JS tests found (*.test.js, *.spec.js, test/, tests/)"
        val limited = tests.take(40)
        var pass = 0
        var fail = 0
        val blocks = mutableListOf<String>()
        for (file in limited) {
            val result = exec(root, file)
            val fileFail = result.fail + if (result.thrown != null) 1 else 0
            pass += result.pass
            fail += fileFail
            blocks.add(buildString {
                append(RepoFiles.rel(file, root))
                append("  pass ").append(result.pass)
                append(" fail ").append(fileFail)
                if (result.output.isNotBlank()) {
                    append('\n')
                    append(result.output.trimEnd().prependIndent("  "))
                }
                result.errors.forEach { append("\n  ").append(it) }
                if (result.thrown != null) append("\n  error: ").append(result.thrown)
            })
        }
        val more = if (tests.size > limited.size) "\nstopped after ${limited.size} test files" else ""
        return blocks.joinToString("\n") + "\nTOTAL pass $pass fail $fail" + more
    }

    fun runAuto(root: File, cwd: File): Result {
        findHtml(cwd, root)?.let { return Result.Html(it) }
        findEntry(cwd, root)?.let { return Result.Text(runJs(root, it)) }
        return Result.Text("nothing to run\n${detect(root)}")
    }

    fun runJs(root: File, file: File): String {
        if (!file.isFile) return "no such file: ${file.name}"
        if (file.length() > MAX_JS) return "file too large"
        val result = exec(root, file)
        return buildString {
            if (result.output.isNotBlank()) append(result.output.trimEnd()).append('\n')
            result.errors.forEach { append(it).append('\n') }
            if (result.thrown != null) append("error: ").append(result.thrown).append('\n')
            if (result.pass + result.fail > 0) append("pass ${result.pass} fail ${result.fail}\n")
            if (isEmpty()) append("(no output)")
        }.trimEnd()
    }

    fun detect(root: File): String {
        val names = root.list()?.toSet().orEmpty()
        return when {
            "index.html" in names -> "found index.html — run opens a preview"
            "package.json" in names -> "node project — relative require() can run; npm packages cannot"
            "build.gradle" in names || "build.gradle.kts" in names ||
                "settings.gradle" in names || "settings.gradle.kts" in names ->
                "gradle project — this phone cannot compile it"
            "Cargo.toml" in names -> "rust project — this phone cannot compile it"
            "go.mod" in names -> "go project — this phone cannot compile it"
            "pom.xml" in names -> "maven project — this phone cannot compile it"
            "pyproject.toml" in names || "requirements.txt" in names || "setup.py" in names ->
                "python project — this phone cannot run it"
            else -> "no index.html or JavaScript entrypoint"
        }
    }

    private data class Exec(
        val output: String,
        val pass: Int,
        val fail: Int,
        val errors: List<String>,
        val thrown: String?
    )

    private fun exec(root: File, file: File): Exec {
        if (!file.isFile) return Exec("", 0, 1, emptyList(), "no such file")
        if (file.length() > MAX_JS) return Exec("", 0, 1, emptyList(), "file too large")
        val output = StringBuilder()
        val errors = mutableListOf<String>()
        val pass = intArrayOf(0)
        val fail = intArrayOf(0)
        return try {
            JsRuntime.use { cx ->
                val scope = cx.initSafeStandardObjects()
                ScriptableObject.putProperty(scope, "print", fn { args ->
                    output.append(argsText(args)).append('\n')
                    Context.getUndefinedValue()
                })
                val assertFn = fn { args ->
                    val ok = args != null && args.isNotEmpty() && Context.toBoolean(args[0])
                    if (ok) {
                        pass[0]++
                    } else {
                        fail[0]++
                        val msg = if (args != null && args.size > 1) Context.toString(args[1]) else "assertion failed"
                        errors.add(msg)
                    }
                    Context.getUndefinedValue()
                }
                ScriptableObject.putProperty(scope, "assert", assertFn)
                cx.evaluateString(scope, prelude, "prelude", 1, null)
                val loader = Loader(cx, root, scope)
                val startDir = file.parentFile ?: root
                ScriptableObject.putProperty(scope, "require", loader.requireFn(startDir))
                cx.evaluateString(
                    scope,
                    "var module = { exports: {} }; var exports = module.exports;",
                    "module",
                    1,
                    null
                )
                cx.evaluateString(scope, prepare(file), RepoFiles.rel(file, root), 1, null)
            }
            Exec(output.toString(), pass[0], fail[0], errors.toList(), null)
        } catch (e: RhinoException) {
            Exec(output.toString(), pass[0], fail[0], errors.toList(), rhinoMessage(file, root, e))
        } catch (e: Exception) {
            Exec(output.toString(), pass[0], fail[0], errors.toList(), e.message ?: e.toString())
        }
    }

    private class Loader(val cx: Context, val root: File, val top: Scriptable) {
        private val cache = HashMap<String, Any?>()
        private val loading = HashSet<String>()

        fun requireFn(dir: File) = object : JsFn() {
            override fun invoke(args: Array<Any?>?): Any? {
                val spec = if (args == null || args.isEmpty()) "" else Context.toString(args[0])
                if (spec == "assert" || spec == "node:assert" || spec == "node:assert/strict") {
                    return top.get("assert", top)
                }
                if (!spec.startsWith(".")) {
                    throw EvaluatorException("only relative require() works on device, not '$spec'")
                }
                val resolved = JsRunner.resolveModule(dir, root, spec)
                if (cache.containsKey(resolved.path)) return cache[resolved.path]
                if (!loading.add(resolved.path)) throw EvaluatorException("circular require: $spec")
                try {
                    val exports = evalModule(resolved)
                    cache[resolved.path] = exports
                    return exports
                } finally {
                    loading.remove(resolved.path)
                }
            }
        }

        private fun evalModule(file: File): Any? {
            val scope = cx.newObject(top)
            scope.parentScope = top
            ScriptableObject.putProperty(scope, "require", requireFn(file.parentFile ?: root))
            cx.evaluateString(
                scope,
                "var module = { exports: {} }; var exports = module.exports;",
                file.name,
                1,
                null
            )
            cx.evaluateString(scope, JsRunner.prepare(file), RepoFiles.rel(file, root), 1, null)
            val moduleObj = scope.get("module", scope)
            if (moduleObj !is Scriptable) return Context.getUndefinedValue()
            val exports = moduleObj.get("exports", moduleObj)
            return if (exports == Scriptable.NOT_FOUND) Context.getUndefinedValue() else exports
        }
    }

    private fun resolveModule(dir: File, root: File, spec: String): File {
        val direct = File(dir, spec)
        val file = listOf(direct, File(dir, "$spec.js"), File(direct, "index.js")).firstOrNull { it.isFile }
            ?: throw EvaluatorException("not found: $spec")
        val canon = file.canonicalFile
        val base = root.canonicalFile
        if (canon != base && !canon.path.startsWith(base.path + File.separator)) {
            throw EvaluatorException("require escapes the repo")
        }
        if (canon.length() > MAX_JS) throw EvaluatorException("file too large: $spec")
        return canon
    }

    private fun prepare(file: File): String {
        var text = file.readText()
        if (text.startsWith("\uFEFF")) text = text.substring(1)
        if (text.startsWith("#!")) text = text.substringAfter('\n', "")
        return text
    }

    private fun jsFiles(root: File): Pair<List<File>, Boolean> {
        val out = mutableListOf<File>()
        var capped = false
        RepoFiles.walk(root) { file ->
            if (out.size >= 80) {
                capped = true
                return@walk
            }
            if (file.extension.lowercase() !in RepoFiles.JS_EXT) return@walk
            if (file.name.endsWith(".min.js") || file.length() > MAX_JS) return@walk
            out.add(file)
        }
        return out to capped
    }

    private fun findTests(root: File): List<File> {
        val out = mutableListOf<File>()
        RepoFiles.walk(root) { file ->
            if (out.size >= 80) return@walk
            if (isTest(root, file) && file.length() <= MAX_JS) out.add(file)
        }
        return out
    }

    private fun isTest(root: File, file: File): Boolean {
        if (file.extension.lowercase() !in RepoFiles.JS_EXT) return false
        if (file.name.endsWith(".min.js")) return false
        val rel = RepoFiles.rel(file, root)
        val name = file.name
        return name.substringBeforeLast('.').endsWith(".test") ||
            name.substringBeforeLast('.').endsWith(".spec") ||
            rel.startsWith("test/") ||
            rel.startsWith("tests/")
    }

    private fun findHtml(cwd: File, root: File): File? {
        val local = File(cwd, "index.html")
        if (local.isFile) return local
        val top = File(root, "index.html")
        if (top.isFile) return top
        return null
    }

    private fun findEntry(cwd: File, root: File): File? {
        val names = listOf("main.js", "index.js", "app.js")
        for (dir in listOf(cwd, root, File(root, "src"))) {
            for (name in names) {
                val file = File(dir, name)
                if (file.isFile) return file
            }
        }
        return null
    }

    private fun lineOf(e: RhinoException): Int = if (e.lineNumber() > 0) e.lineNumber() else 0

    private fun rhinoMessage(file: File, root: File, e: RhinoException): String {
        val msg = e.message ?: e.toString()
        return "$msg (${RepoFiles.rel(file, root)}:${lineOf(e)})"
    }

    private fun argsText(args: Array<Any?>?): String {
        if (args == null || args.isEmpty()) return ""
        return args.joinToString(" ") { Context.toString(it) }
    }

    private fun fn(block: (Array<Any?>?) -> Any?) = object : JsFn() {
        override fun invoke(args: Array<Any?>?): Any? = block(args)
    }
}

private object JsRuntime {
    private val ready = AtomicBoolean(false)
    private val factory = Factory()

    fun resetSteps() {
        factory.steps.set(0)
    }

    fun <T> use(block: (Context) -> T): T {
        ensure()
        resetSteps()
        val cx = factory.enterContext()
        try {
            return block(cx)
        } finally {
            Context.exit()
        }
    }

    private fun ensure() {
        if (ready.get()) return
        synchronized(factory) {
            if (ready.get()) return
            try {
                if (!ContextFactory.hasExplicitGlobal()) ContextFactory.initGlobal(factory)
            } catch (_: IllegalStateException) {
                // Another factory is already global. This instance still enters its own contexts.
            }
            ready.set(true)
        }
    }

    private class Factory : ContextFactory() {
        val steps = AtomicLong()

        override fun makeContext(): Context {
            val cx = super.makeContext()
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            cx.instructionObserverThreshold = 10_000
            return cx
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (steps.addAndGet(instructionCount.toLong()) > 8_000_000L) {
                throw EvaluatorException("stopped: script ran too long")
            }
        }
    }
}
