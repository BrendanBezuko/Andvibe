package com.example.andvibe

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SecretStoreTest {
    private lateinit var secrets: SecretStore

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        secrets = SecretStore(ctx)
    }

    @Test
    fun saveAndReadProviderFields() {
        secrets.save(Provider.OPENAI, "sk-test", "gpt-test", "https://api.openai.com/v1")
        assertEquals("sk-test", secrets.get(Provider.OPENAI, "key", ""))
        assertEquals("gpt-test", secrets.get(Provider.OPENAI, "model", ""))
        assertEquals("https://api.openai.com/v1", secrets.get(Provider.OPENAI, "base", ""))
        assertEquals(Provider.OPENAI.id, secrets.lastProvider())
    }

    @Test
    fun saveAndReadGitFields() {
        secrets.saveGit("Ada", "ada@ex.com", "ada", "ghp_x", "KEY")
        assertEquals("Ada", secrets.gitName())
        assertEquals("ada@ex.com", secrets.gitEmail())
        assertEquals("ada", secrets.gitUser())
        assertEquals("ghp_x", secrets.gitToken())
        assertEquals("KEY", secrets.gitSsh())
    }

    @Test
    fun usesPlainPrefsWhenKeystoreUnavailable() {
        // Robolectric typically cannot open AndroidKeyStore → plain prefs path.
        assertTrue(secrets.plain)
    }
}
