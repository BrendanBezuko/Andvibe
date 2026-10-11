package com.example.andvibe.core

import kotlin.math.ln

/**
 * Pre-push secret scanner. gitleaks doesn't run on Android, so this carries a small set of its
 * high-signal default rules plus a few file-name rules. A line containing `gitleaks:allow` or
 * `secretscan:allow` is skipped, as with gitleaks.
 */
object SecretScan {
    /** Text about to leave the device: added lines (1-based numbers) of one file. */
    data class Outgoing(val path: String, val lines: List<Pair<Int, String>>)

    data class Finding(val path: String, val line: Int, val rule: String, val excerpt: String) {
        val label: String get() = if (line > 0) "$path:$line  $rule  $excerpt" else "$path  $rule"
    }

    private class Rule(val id: String, val regex: Regex, val group: Int = 0, val minEntropy: Double = 0.0)

    private val rules = listOf(
        Rule("Private key", Regex("""-----BEGIN[ A-Z0-9_-]{0,100}PRIVATE KEY( BLOCK)?-----""")),
        Rule("AWS access key", Regex("""\b((?:A3T[A-Z0-9]|AKIA|ASIA|ABIA|ACCA)[A-Z2-7]{16})\b"""), 1),
        Rule("GitHub token", Regex("""\b(gh[pousr]_[0-9a-zA-Z]{36})\b"""), 1),
        Rule("GitHub fine-grained token", Regex("""\b(github_pat_[0-9a-zA-Z_]{82})\b"""), 1),
        Rule("GitLab token", Regex("""\b(glpat-[0-9a-zA-Z_-]{20})\b"""), 1),
        Rule("Slack token", Regex("""\b(xox[baprs]-[0-9a-zA-Z-]{10,})"""), 1),
        Rule("Slack webhook", Regex("""(https://hooks\.slack\.com/services/[A-Za-z0-9+/]{43,})"""), 1),
        Rule("Google API key", Regex("""\b(AIza[0-9A-Za-z_-]{35})\b"""), 1),
        Rule("Stripe key", Regex("""\b((?:sk|rk)_(?:test|live|prod)_[0-9a-zA-Z]{10,99})\b"""), 1),
        Rule("Anthropic API key", Regex("""\b(sk-ant-(?:api03|admin01)-[A-Za-z0-9_-]{80,})"""), 1),
        Rule("OpenAI API key", Regex("""\b(sk-(?:proj|svcacct|admin)-[A-Za-z0-9_-]{40,}|sk-[A-Za-z0-9]{20}T3BlbkFJ[A-Za-z0-9]{20})"""), 1),
        Rule("npm token", Regex("""\b(npm_[A-Za-z0-9]{36})\b"""), 1),
        Rule("JSON web token", Regex("""\b(eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,})"""), 1),
        Rule(
            "Hard-coded secret",
            Regex("""(?i)(?:api[_-]?key|secret|token|passw(?:or)?d|client[_-]?secret|auth)["']?\s*[:=]\s*["']([A-Za-z0-9_\-+/=.]{16,})["']"""),
            1,
            minEntropy = 3.5,
        ),
    )

    /** Files that are secrets by name, whatever they contain. */
    private val secretFiles = listOf(
        Regex("""(^|/)\.env(\.[A-Za-z0-9_-]+)?$""") to "Environment file",
        Regex("""(^|/)id_(rsa|dsa|ecdsa|ed25519)$""") to "SSH private key",
        Regex("""\.(pem|key|p12|pfx)$""") to "Key file",
        Regex("""\.(jks|keystore)$""") to "Keystore",
        Regex("""(^|/)(credentials|service-account[^/]*)\.json$""") to "Credentials file",
        Regex("""(^|/)\.npmrc$""") to "npm config (may hold a token)",
    )

    /** Template files meant to be committed. */
    private val exampleFile = Regex("""(?i)\.(example|sample|template|dist)$|(^|/)\.env\.(example|sample|template)$""")

    private val placeholder = Regex("""(?i)example|placeholder|your[_-]|changeme|dummy|xxxx|<[^>]+>|\$\{|\{\{""")

    private val allow = Regex("""(?i)(gitleaks|secretscan):allow""")

    fun scan(files: List<Outgoing>, names: List<String> = emptyList()): List<Finding> {
        val out = mutableListOf<Finding>()
        for (name in (names + files.map { it.path }).distinct()) {
            if (exampleFile.containsMatchIn(name)) continue
            secretFiles.firstOrNull { it.first.containsMatchIn(name) }?.let { out += Finding(name, 0, it.second, "") }
        }
        for (file in files) {
            if (exampleFile.containsMatchIn(file.path)) continue
            for ((number, text) in file.lines) {
                if (allow.containsMatchIn(text)) continue
                for (rule in rules) {
                    val match = rule.regex.find(text) ?: continue
                    val secret = match.groups[rule.group]?.value ?: match.value
                    if (rule.minEntropy > 0 && (entropy(secret) < rule.minEntropy || placeholder.containsMatchIn(secret))) continue
                    if (rule.group > 0 && placeholder.containsMatchIn(secret)) continue
                    out += Finding(file.path, number, rule.id, redact(secret))
                    break
                }
            }
        }
        return out
    }

    /** Shows enough to recognize the value without re-leaking it into logs. */
    internal fun redact(secret: String): String =
        if (secret.length <= 8) "****" else secret.take(4) + "…" + "(${secret.length} chars)"

    /** Shannon entropy in bits per char; real keys are high, words and repeats are low. */
    internal fun entropy(s: String): Double {
        if (s.isEmpty()) return 0.0
        return s.groupingBy { it }.eachCount().values.sumOf { c ->
            val p = c.toDouble() / s.length
            -p * ln(p) / ln(2.0)
        }
    }

    fun report(findings: List<Finding>, limit: Int = 12): String = buildString {
        append("Possible secrets in what you're about to push:\n")
        findings.take(limit).forEach { append("  ").append(it.label).append('\n') }
        if (findings.size > limit) append("  …and ${findings.size - limit} more\n")
        append("Remove them (and rotate any real key), or add gitleaks:allow to a line that is safe.")
    }
}
