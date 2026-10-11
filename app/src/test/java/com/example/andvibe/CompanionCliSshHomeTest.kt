package com.example.andvibe

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CompanionCliSshHomeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var secrets: SecretStore

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        secrets = SecretStore(ctx)
        secrets.saveGit("", "", "", "", "")
    }

    @Test
    fun ensureSshHomeWritesPemNewlineAndIdentityFile() {
        val home = tmp.newFolder("home")
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----" // fake fixture; gitleaks:allow
        secrets.saveGit("n", "e", "u", "tok", pem)

        CompanionCli.ensureSshHome(home, secrets)

        val key = File(home, ".ssh/id_andvibe")
        assertTrue(key.isFile)
        assertTrue(key.readText().endsWith("\n"))
        val config = File(home, ".ssh/config").readText()
        assertTrue(config.contains("IdentityFile ${key.absolutePath}"))
        assertTrue(config.contains("IdentitiesOnly yes"))
    }

    @Test
    fun ensureSshHomeDeletesKeyWhenBlank() {
        val home = tmp.newFolder("home2")
        val key = File(home, ".ssh/id_andvibe").also {
            it.parentFile!!.mkdirs()
            it.writeText("old-key\n")
        }
        secrets.saveGit("", "", "", "", "")

        CompanionCli.ensureSshHome(home, secrets)

        assertFalse(key.exists())
        val config = File(home, ".ssh/config").readText()
        assertFalse(config.contains("IdentityFile"))
    }
}
