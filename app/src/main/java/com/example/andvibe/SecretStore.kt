package com.example.andvibe

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class SecretStore(context: Context) {
    val plain: Boolean
    private val prefs: SharedPreferences

    init {
        val app = context.applicationContext
        val encrypted = createEncrypted(app)
        if (encrypted != null) {
            prefs = encrypted
            plain = false
        } else {
            prefs = app.getSharedPreferences("andvibe_secrets_plain", Context.MODE_PRIVATE)
            plain = true
        }
    }

    fun get(provider: Provider, field: String, default: String): String {
        val key = "${provider.id}_$field"
        return if (prefs.contains(key)) prefs.getString(key, default) ?: default else default
    }

    fun save(provider: Provider, apiKey: String, model: String, base: String) {
        prefs.edit()
            .putString("${provider.id}_key", apiKey)
            .putString("${provider.id}_model", model)
            .putString("${provider.id}_base", base)
            .putString("provider", provider.id)
            .apply()
    }

    fun lastProvider(): String = prefs.getString("provider", Provider.OPENAI.id) ?: Provider.OPENAI.id

    fun autoTest(): Boolean = if (prefs.contains("auto")) prefs.getBoolean("auto", true) else true

    fun setAutoTest(value: Boolean) {
        prefs.edit().putBoolean("auto", value).apply()
    }

    @Suppress("DEPRECATION")
    private fun createEncrypted(context: Context): SharedPreferences? {
        return try {
            val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "andvibe_secrets",
                alias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            null
        }
    }
}
