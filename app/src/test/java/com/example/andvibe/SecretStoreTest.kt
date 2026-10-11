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

    @Test
    fun customServersRoundTripAndSelect() {
        val a = CustomServer.new(
            name = "claude-proxy",
            key = "proxy-key",
            model = "claude-code",
            base = "http://192.168.1.10:8787",
            style = ApiStyle.ANTHROPIC,
        )
        val b = CustomServer.new(
            name = "local-openai",
            key = "sk-local",
            model = "gpt-local",
            base = "http://127.0.0.1:11434/v1",
            style = ApiStyle.OPENAI,
        )
        secrets.saveCustomServers(listOf(a, b), selectedId = a.id)
        assertEquals(2, secrets.customServers().size)
        assertEquals(a.id, secrets.selectedCustomId())
        assertEquals(ApiStyle.ANTHROPIC, secrets.apiStyle(Provider.CUSTOM))
        assertEquals("proxy-key", secrets.get(Provider.CUSTOM, "key", ""))
        assertEquals("claude-code", secrets.get(Provider.CUSTOM, "model", ""))

        secrets.selectCustomServer(b.id)
        assertEquals(ApiStyle.OPENAI, secrets.apiStyle(Provider.CUSTOM))
        assertEquals("sk-local", secrets.get(Provider.CUSTOM, "key", ""))
        assertEquals("http://127.0.0.1:11434/v1", secrets.get(Provider.CUSTOM, "base", ""))
    }

    @Test
    fun customProviderSaveUpdatesActiveServer() {
        secrets.save(Provider.CUSTOM, "legacy-key", "legacy-model", "http://host:9")
        val active = secrets.activeCustomServer()
        assertTrue(active != null)
        assertEquals("legacy-key", active!!.key)
        assertEquals("legacy-model", active.model)
        assertEquals("http://host:9", active.base)
        assertEquals(Provider.CUSTOM.id, secrets.lastProvider())
    }
}
