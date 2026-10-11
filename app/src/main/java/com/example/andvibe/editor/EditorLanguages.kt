package com.example.andvibe.editor

/** File name → TextMate scope registered in assets/textmate/languages.json. */
object EditorLanguages {
    private val byName = mapOf(
        "dockerfile" to null,
        ".bashrc" to "source.shell",
        ".zshrc" to "source.shell",
        ".profile" to "source.shell",
        ".editorconfig" to "source.ini",
        ".gitconfig" to "source.ini",
        ".npmrc" to "source.ini",
        "tsconfig.json" to "source.json.comments",
        "jsconfig.json" to "source.json.comments",
        ".eslintrc.json" to "source.json.comments",
    )

    private val byExtension = mapOf(
        "kt" to "source.kotlin", "kts" to "source.kotlin",
        "java" to "source.java",
        "gradle" to "source.groovy", "groovy" to "source.groovy",
        "js" to "source.js", "mjs" to "source.js", "cjs" to "source.js",
        "jsx" to "source.js.jsx",
        "ts" to "source.ts", "mts" to "source.ts", "cts" to "source.ts",
        "tsx" to "source.tsx",
        "json" to "source.json", "webmanifest" to "source.json",
        "jsonc" to "source.json.comments", "json5" to "source.json.comments",
        "css" to "source.css",
        "py" to "source.python", "pyw" to "source.python",
        "sh" to "source.shell", "bash" to "source.shell", "zsh" to "source.shell",
        "yml" to "source.yaml", "yaml" to "source.yaml",
        "properties" to "source.ini", "ini" to "source.ini", "cfg" to "source.ini",
        "conf" to "source.ini", "toml" to "source.ini", "pro" to "source.ini",
        "xml" to "text.xml", "svg" to "text.xml", "xsd" to "text.xml", "plist" to "text.xml",
        "html" to "text.html.basic", "htm" to "text.html.basic",
        "md" to "text.html.markdown", "markdown" to "text.html.markdown",
    )

    /** Null for plain text (no highlighting). */
    fun scopeFor(fileName: String): String? {
        val name = fileName.substringAfterLast('/').lowercase()
        if (name in byName) return byName[name]
        val ext = name.substringAfterLast('.', "")
        if (ext.isEmpty() || ext == name.removePrefix(".")) return null
        return byExtension[ext]
    }

    /** Short label for the editor status line, e.g. "Kotlin". */
    fun label(scope: String?): String = when (scope) {
        null -> "Plain text"
        "source.kotlin" -> "Kotlin"
        "source.java" -> "Java"
        "source.groovy" -> "Groovy"
        "source.js" -> "JavaScript"
        "source.js.jsx" -> "JavaScript React"
        "source.ts" -> "TypeScript"
        "source.tsx" -> "TypeScript React"
        "source.json" -> "JSON"
        "source.json.comments" -> "JSON with comments"
        "source.css" -> "CSS"
        "source.python" -> "Python"
        "source.shell" -> "Shell"
        "source.yaml" -> "YAML"
        "source.ini" -> "INI / properties"
        "text.xml" -> "XML"
        "text.html.basic" -> "HTML"
        "text.html.markdown" -> "Markdown"
        else -> scope
    }
}
