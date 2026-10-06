package com.example.andvibe

import com.example.andvibe.core.RepoFiles

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class UnderstandResult(val markdown: String, val defs: Int, val files: Int)

object Understand {
    private const val OUT_NAME = "UNDERSTAND.md"
    private const val MAX_DEFS = 400
    private const val MAX_FILES = 120
    private const val MAX_SNIPPETS = 12
    private const val SNIPPET_LINES = 80

    private val sourceExt = setOf(
        "kt", "kts", "java", "js", "mjs", "cjs", "ts", "tsx", "jsx",
        "py", "go", "rs", "swift", "m", "mm", "c", "cc", "cpp", "h", "hpp",
        "rb", "php", "cs", "scala", "dart"
    )

    private val keyNames = setOf(
        "readme.md", "readme", "agents.md", "claude.md", ".cursorrules",
        "mainactivity.kt", "main.kt", "app.kt", "application.kt",
        "index.js", "index.ts", "index.mjs", "app.js", "app.ts", "server.js",
        "server.py", "main.py", "main.go", "main.rs", "app.py"
    )

    private val systemPrompt get() = PromptStore.get(PromptStore.Kind.UNDERSTAND)

    fun outFile(root: File): File = File(root, OUT_NAME)

    fun loadSaved(root: File): String? {
        val file = outFile(root)
        if (!file.isFile || file.length() > 1_500_000) return null
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun save(root: File, markdown: String): File {
        if (markdown.isBlank()) error("nothing to save")
        val file = outFile(root)
        file.writeText(markdown.trimEnd() + "\n")
        return file
    }

    fun run(
        root: File,
        focus: String,
        provider: Provider,
        key: String,
        model: String,
        base: String,
        stop: AtomicBoolean,
        onStep: (String) -> Unit
    ): UnderstandResult {
        if (stop.get()) error("stopped")
        onStep("Scanning source for function defs")
        val defs = scan(root)
        if (stop.get()) error("stopped")
        onStep("Found ${defs.size} defs in ${defs.map { it.path }.toSet().size} files")
        val packed = pack(root, focus, defs)
        if (stop.get()) error("stopped")
        onStep("Asking ${provider.label} for ratings and diagrams")
        val prose = AiClient.complete(systemPrompt, packed, provider, key, model, base, maxUser = 80_000)
        if (stop.get()) error("stopped")
        val markdown = assemble(root.name, prose, defs, focus)
        return UnderstandResult(markdown, defs.size, defs.map { it.path }.toSet().size)
    }

    data class Def(val path: String, val line: Int, val signature: String)

    fun scan(root: File): List<Def> {
        val out = mutableListOf<Def>()
        var files = 0
        RepoFiles.walk(root) { file ->
            if (out.size >= MAX_DEFS || files >= MAX_FILES) return@walk
            if (!isSource(file) || file.length() > 400_000 || RepoFiles.looksBinary(file)) return@walk
            files++
            val path = RepoFiles.rel(file, root)
            val ext = file.extension.lowercase()
            var number = 0
            file.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    number++
                    if (out.size >= MAX_DEFS) break
                    val line = raw.trimEnd()
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#") ||
                        trimmed.startsWith("*") || trimmed.startsWith("/*")
                    ) {
                        continue
                    }
                    val sig = signature(ext, trimmed) ?: continue
                    out.add(Def(path, number, sig.take(220)))
                }
            }
        }
        return out
    }

    private fun pack(root: File, focus: String, defs: List<Def>): String {
        return buildString {
            append("Repo: ").append(root.name).append('\n')
            append("Gradle wrapper: ").append(if (File(root, "gradlew").isFile) "yes" else "no").append('\n')
            if (focus.isNotBlank()) {
                append("User focus: ").append(focus.take(2_000)).append("\n\n")
            } else {
                append("User focus: whole project\n\n")
            }
            append("File tree:\n").append(AiClient.tree(root, 160)).append("\n\n")
            append("Scanned function and method signatures (source of truth):\n")
            if (defs.isEmpty()) {
                append("(none found)\n\n")
            } else {
                var budget = 28_000
                var lastPath = ""
                for (def in defs) {
                    val block = buildString {
                        if (def.path != lastPath) {
                            append("\n").append(def.path).append('\n')
                            lastPath = def.path
                        }
                        append("  L").append(def.line).append(": ").append(def.signature).append('\n')
                    }
                    if (block.length > budget) break
                    append(block)
                    budget -= block.length
                }
                append('\n')
            }
            val snippets = chooseSnippets(root)
            if (snippets.isNotEmpty()) {
                append("Key file heads:\n")
                for ((path, text) in snippets) {
                    append("----- ").append(path).append(" -----\n")
                    append(text)
                    if (!text.endsWith("\n")) append('\n')
                    append("----- end -----\n")
                }
            }
            append("\nWrite the documentation now. Start with Ratings graphs and Issues, then Overview, Flow, Sequence, and Key points.")
        }
    }

    private fun assemble(repo: String, prose: String, defs: List<Def>, focus: String): String {
        val body = prose.trim()
            .removePrefix("```markdown")
            .removePrefix("```md")
            .removeSuffix("```")
            .trim()
            .ifBlank { "# $repo — How it works\n\n## Overview\n(no model reply)\n" }
        return buildString {
            append(body.trimEnd())
            append("\n\n## Function definitions\n")
            if (focus.isNotBlank()) {
                append("\nFocus was: ").append(focus.trim()).append("\n")
            }
            if (defs.isEmpty()) {
                append("\nNo function or method signatures were found in scanned source files.\n")
            } else {
                append("\nScanned from the repo on the phone. Line numbers are 1-based.\n")
                var lastPath = ""
                for (def in defs) {
                    if (def.path != lastPath) {
                        append("\n### `").append(def.path).append("`\n\n")
                        lastPath = def.path
                    }
                    append("- L").append(def.line).append(": `").append(def.signature).append("`\n")
                }
            }
            append('\n')
        }
    }

    private fun chooseSnippets(root: File): List<Pair<String, String>> {
        val ranked = mutableListOf<Pair<Int, File>>()
        RepoFiles.walk(root) { file ->
            if (!isSource(file) && file.name.lowercase() !in keyNames) return@walk
            if (file.length() > 80_000 || RepoFiles.looksBinary(file)) return@walk
            val name = file.name.lowercase()
            val rel = RepoFiles.rel(file, root).lowercase()
            val score = when {
                name in keyNames -> 100
                name.startsWith("main") -> 80
                name.startsWith("app") && (name.endsWith(".kt") || name.endsWith(".java") || name.endsWith(".py")) -> 70
                rel.contains("/server") || rel.endsWith("server.py") || rel.endsWith("server.js") -> 65
                name.endsWith("activity.kt") || name.endsWith("activity.java") -> 60
                name == "package.json" || name == "build.gradle.kts" || name == "build.gradle" -> 50
                else -> 0
            }
            if (score > 0) ranked.add(score to file)
        }
        ranked.sortByDescending { it.first }
        val out = mutableListOf<Pair<String, String>>()
        var budget = 24_000
        val seen = HashSet<String>()
        for ((_, file) in ranked) {
            if (out.size >= MAX_SNIPPETS || budget <= 0) break
            val path = RepoFiles.rel(file, root)
            if (!seen.add(path)) continue
            val text = file.bufferedReader().use { reader ->
                buildString {
                    repeat(SNIPPET_LINES) {
                        val line = reader.readLine() ?: return@buildString
                        append(line).append('\n')
                    }
                }
            }.take(budget)
            if (text.isBlank()) continue
            out.add(path to text)
            budget -= text.length
        }
        return out
    }

    private fun isSource(file: File): Boolean {
        val name = file.name
        if (name == "package-lock.json" || name == "yarn.lock" || name.endsWith(".min.js")) return false
        if (name.lowercase() in keyNames) return true
        return file.extension.lowercase() in sourceExt
    }

    private fun signature(ext: String, line: String): String? {
        return when (ext) {
            "kt", "kts" -> kotlinSig(line)
            "java" -> javaSig(line)
            "js", "mjs", "cjs", "ts", "tsx", "jsx" -> jsSig(line)
            "py" -> pythonSig(line)
            "go" -> goSig(line)
            "rs" -> rustSig(line)
            "swift" -> swiftSig(line)
            "rb" -> rubySig(line)
            "php" -> phpSig(line)
            "cs" -> csharpSig(line)
            "dart" -> dartSig(line)
            "c", "cc", "cpp", "h", "hpp", "m", "mm" -> cSig(line)
            "scala" -> scalaSig(line)
            else -> null
        }
    }

    private fun kotlinSig(line: String): String? {
        val funMatch = Regex(
            """^(?:(?:public|private|protected|internal|open|override|abstract|suspend|inline|operator|tailrec)\s+)*fun\s+(?:<[^>]+>\s*)?([A-Za-z_][\w.]*)\s*(\(.*)$"""
        ).find(line) ?: return classOrObject(line)
        return "fun ${funMatch.groupValues[1]}${trimParams(funMatch.groupValues[2])}"
    }

    private fun classOrObject(line: String): String? {
        val m = Regex(
            """^(?:(?:public|private|protected|internal|open|abstract|data|sealed|enum|inner|annotation)\s+)*(class|object|interface)\s+([A-Za-z_]\w*).*$"""
        ).find(line) ?: return null
        return "${m.groupValues[1]} ${m.groupValues[2]}"
    }

    private fun javaSig(line: String): String? {
        val type = Regex("""^(?:public|protected|private|static|final|native|synchronized|abstract|default|\s)+[\w.<>,\[\]?]+\s+([A-Za-z_]\w*)\s*(\(.*)$""")
            .find(line)
        if (type != null && type.groupValues[1] !in setOf("if", "for", "while", "switch", "catch", "return", "new")) {
            return "${type.groupValues[1]}${trimParams(type.groupValues[2])}"
        }
        val cls = Regex("""^(?:public|protected|private|static|final|abstract|\s)*(class|interface|enum|record)\s+([A-Za-z_]\w*).*$""")
            .find(line) ?: return null
        return "${cls.groupValues[1]} ${cls.groupValues[2]}"
    }

    private fun jsSig(line: String): String? {
        Regex("""^(?:export\s+)?(?:async\s+)?function\s*\*?\s*([A-Za-z_$][\w$]*)\s*(\(.*)$""")
            .find(line)?.let { return "function ${it.groupValues[1]}${trimParams(it.groupValues[2])}" }
        Regex("""^(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s*)?(?:function\*?|\([^)]*\)\s*=>|[A-Za-z_$][\w$]*\s*=>)""")
            .find(line)?.let { return "${it.groupValues[1]} = …" }
        Regex("""^(?:async\s+)?([A-Za-z_$][\w$]*)\s*(\([^;]*\))\s*\{?\s*$""")
            .find(line)?.let {
                val name = it.groupValues[1]
                if (name in setOf("if", "for", "while", "switch", "catch", "function")) return null
                return "$name${trimParams(it.groupValues[2])}"
            }
        Regex("""^(?:export\s+)?(?:default\s+)?(?:abstract\s+)?class\s+([A-Za-z_$][\w$]*).*$""")
            .find(line)?.let { return "class ${it.groupValues[1]}" }
        return null
    }

    private fun pythonSig(line: String): String? {
        Regex("""^(?:async\s+)?def\s+([A-Za-z_]\w*)\s*(\(.*)$""")
            .find(line)?.let { return "def ${it.groupValues[1]}${trimParams(it.groupValues[2])}" }
        Regex("""^class\s+([A-Za-z_]\w*).*$""").find(line)?.let { return "class ${it.groupValues[1]}" }
        return null
    }

    private fun goSig(line: String): String? {
        Regex("""^func\s+(?:\([^)]+\)\s*)?([A-Za-z_]\w*)\s*(\(.*)$""")
            .find(line)?.let { return "func ${it.groupValues[1]}${trimParams(it.groupValues[2])}" }
        Regex("""^type\s+([A-Za-z_]\w*)\s+(struct|interface)\b.*$""")
            .find(line)?.let { return "type ${it.groupValues[1]} ${it.groupValues[2]}" }
        return null
    }

    private fun rustSig(line: String): String? {
        Regex("""^(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?(?:unsafe\s+)?fn\s+([A-Za-z_]\w*)\s*(?:<[^>]+>)?\s*(\(.*)$""")
            .find(line)?.let { return "fn ${it.groupValues[1]}${trimParams(it.groupValues[2])}" }
        Regex("""^(?:pub(?:\([^)]*\))?\s+)?(?:struct|enum|trait|impl)\s+([A-Za-z_]\w*).*$""")
            .find(line)?.let { return it.value.take(120) }
        return null
    }

    private fun swiftSig(line: String): String? {
        Regex("""^(?:(?:public|private|internal|open|fileprivate|static|class|override|mutating)\s+)*func\s+([A-Za-z_]\w*)\s*(\(.*)$""")
            .find(line)?.let { return "func ${it.groupValues[1]}${trimParams(it.groupValues[2])}" }
        Regex("""^(?:(?:public|private|internal|open|fileprivate)\s+)*(class|struct|enum|protocol)\s+([A-Za-z_]\w*).*$""")
            .find(line)?.let { return "${it.groupValues[1]} ${it.groupValues[2]}" }
        return null
    }

    private fun rubySig(line: String): String? {
        Regex("""^def\s+([A-Za-z_]\w*[!?=]?).*""").find(line)?.let { return it.value.take(120) }
        Regex("""^class\s+([A-Za-z_]\w*).*$""").find(line)?.let { return "class ${it.groupValues[1]}" }
        return null
    }

    private fun phpSig(line: String): String? {
        Regex("""^(?:(?:public|private|protected|static|final)\s+)*function\s+([A-Za-z_]\w*)\s*(\(.*)$""")
            .find(line)?.let { return "function ${it.groupValues[1]}${trimParams(it.groupValues[2])}" }
        Regex("""^(?:abstract\s+|final\s+)?class\s+([A-Za-z_]\w*).*$""")
            .find(line)?.let { return "class ${it.groupValues[1]}" }
        return null
    }

    private fun csharpSig(line: String): String? {
        Regex("""^(?:(?:public|private|protected|internal|static|virtual|override|async|partial)\s+)+[\w.<>,\[\]?]+\s+([A-Za-z_]\w*)\s*(\(.*)$""")
            .find(line)?.let {
                if (it.groupValues[1] in setOf("if", "for", "while", "switch", "catch")) return null
                return "${it.groupValues[1]}${trimParams(it.groupValues[2])}"
            }
        Regex("""^(?:(?:public|private|protected|internal|static|abstract|sealed|partial)\s+)*(class|interface|struct|record|enum)\s+([A-Za-z_]\w*).*$""")
            .find(line)?.let { return "${it.groupValues[1]} ${it.groupValues[2]}" }
        return null
    }

    private fun dartSig(line: String): String? {
        Regex("""^(?:(?:static|factory|external)\s+)?[\w.<>,\?]+\s+([A-Za-z_]\w*)\s*(\(.*)$""")
            .find(line)?.let {
                if (it.groupValues[1] in setOf("if", "for", "while", "switch", "return")) return null
                return "${it.groupValues[1]}${trimParams(it.groupValues[2])}"
            }
        Regex("""^(?:abstract\s+)?class\s+([A-Za-z_]\w*).*$""").find(line)?.let { return "class ${it.groupValues[1]}" }
        return null
    }

    private fun cSig(line: String): String? {
        if (line.startsWith("#")) return null
        Regex("""^(?:[\w*&\s]+)\b([A-Za-z_]\w*)\s*(\([^;]*\))\s*(?:const)?\s*\{?\s*$""")
            .find(line)?.let {
                val name = it.groupValues[1]
                if (name in setOf("if", "for", "while", "switch", "return", "sizeof")) return null
                return "$name${trimParams(it.groupValues[2])}"
            }
        return null
    }

    private fun scalaSig(line: String): String? {
        Regex("""^(?:(?:override|private|protected|final|implicit)\s+)*def\s+([A-Za-z_]\w*)\s*(?:\[.*\])?\s*(\(.*)$""")
            .find(line)?.let { return "def ${it.groupValues[1]}${trimParams(it.groupValues[2])}" }
        Regex("""^(?:(?:private|protected|final|sealed|abstract|case)\s+)*(class|object|trait)\s+([A-Za-z_]\w*).*$""")
            .find(line)?.let { return "${it.groupValues[1]} ${it.groupValues[2]}" }
        return null
    }

    private fun trimParams(raw: String): String {
        val open = raw.indexOf('(')
        if (open < 0) return "()"
        var depth = 0
        for (i in open until raw.length) {
            when (raw[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        val params = raw.substring(open, i + 1)
                        return if (params.length <= 100) params else "(…)"
                    }
                }
            }
        }
        return "(…)"
    }
}
