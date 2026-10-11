package com.example.andvibe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake secrets are assembled at runtime so this file never trips a real secret scanner. */
class SecretScanTest {
    private val ghToken = "gh" + "p_" + "aB3dE5fG7hJ9kL1mN3pQ5rS7tU9vW1xY3zA5"
    private val awsKey = "AK" + "IA" + "Q4ZB7XKRL2M5NPTW"
    private val googleKey = "AI" + "za" + "SyD3k9Qw7Lm2Np4Rt6Vx8Zb1Cd3Fg5Hj7Kq"
    private val pemHeader = "-----BEGIN " + "RSA PRIVATE KEY-----"

    private fun scan(path: String, vararg lines: String) =
        SecretScan.scan(listOf(SecretScan.Outgoing(path, lines.mapIndexed { i, l -> i + 1 to l })))

    @Test
    fun findsKnownTokenShapes() {
        val findings = scan(
            "src/Config.kt",
            "val ok = 1",
            "val gh = \"$ghToken\"",
            "aws_access_key_id = $awsKey",
            "const key = '$googleKey'",
            pemHeader,
        )
        assertEquals(listOf(2, 3, 4, 5), findings.map { it.line })
        assertEquals(listOf("GitHub token", "AWS access key", "Google API key", "Private key"), findings.map { it.rule })
    }

    @Test
    fun excerptIsRedacted() {
        val finding = scan("a.kt", "t = \"$ghToken\"").single()
        assertTrue(finding.excerpt.startsWith("ghp_"))
        assertTrue(ghToken !in finding.excerpt)
        assertTrue(ghToken !in SecretScan.report(listOf(finding)))
    }

    @Test
    fun genericRuleNeedsEntropyAndSkipsPlaceholders() {
        val real = "api_key = \"" + "q8Zr2Lx9Vb4Nc7Mw1Kp5Ts3Hd6Fg0Ja" + "\""
        assertEquals(1, scan("a.py", real).size)
        assertTrue(scan("a.py", "password = \"aaaaaaaaaaaaaaaaaaaa\"").isEmpty())
        assertTrue(scan("a.py", "api_key = \"YOUR_API_KEY_GOES_HERE_123\"").isEmpty())
        assertTrue(scan("a.py", "token = \"\${'$'}{TOKEN_FROM_ENVIRONMENT}\"").isEmpty())
    }

    @Test
    fun allowCommentSkipsLine() {
        assertTrue(scan("a.kt", "val gh = \"$ghToken\" // gitleaks:allow").isEmpty())
        assertTrue(scan("a.kt", "val gh = \"$ghToken\" // secretscan:allow").isEmpty())
    }

    @Test
    fun secretFileNamesAreFlaggedButTemplatesAreNot() {
        val findings = SecretScan.scan(
            emptyList(),
            listOf(".env", "app/.env.production", "keys/release.jks", "id_rsa", "service-account-prod.json", ".env.example", "src/Main.kt"),
        )
        assertEquals(
            listOf(".env", "app/.env.production", "keys/release.jks", "id_rsa", "service-account-prod.json"),
            findings.map { it.path },
        )
        assertTrue(findings.all { it.line == 0 })
    }

    @Test
    fun entropySeparatesKeysFromWords() {
        assertTrue(SecretScan.entropy("aaaaaaaaaaaaaaaa") < 1.0)
        assertTrue(SecretScan.entropy("q8Zr2Lx9Vb4Nc7Mw1Kp5Ts3Hd6Fg0Ja") > 4.0)
    }

    @Test
    fun reportListsFindingsAndCapsLength() {
        val many = (1..20).map { SecretScan.Finding("f$it.kt", it, "GitHub token", "ghp_…") }
        val text = SecretScan.report(many, limit = 3)
        assertTrue(text.contains("f1.kt:1"))
        assertTrue(text.contains("…and 17 more"))
    }
}
