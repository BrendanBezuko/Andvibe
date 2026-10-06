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
        saveProvider(provider, apiKey, model, base, select = true)
    }

    fun saveChoice(provider: Provider, model: String, base: String) {
        prefs.edit()
            .putString("${provider.id}_model", model)
            .putString("${provider.id}_base", base)
            .putString("provider", provider.id)
            .apply()
    }

    fun saveKey(provider: Provider, apiKey: String) {
        prefs.edit().putString("${provider.id}_key", apiKey).commit()
    }

    fun saveProvider(provider: Provider, apiKey: String, model: String, base: String, select: Boolean) {
        val edit = prefs.edit()
            .putString("${provider.id}_key", apiKey)
            .putString("${provider.id}_model", model)
            .putString("${provider.id}_base", base)
        if (select) edit.putString("provider", provider.id)
        edit.apply()
    }

    fun lastProvider(): String = prefs.getString("provider", Provider.OPENAI.id) ?: Provider.OPENAI.id

    fun autoTest(): Boolean = if (prefs.contains("auto")) prefs.getBoolean("auto", true) else true

    fun setAutoTest(value: Boolean) {
        prefs.edit().putBoolean("auto", value).apply()
    }

    fun gitName(): String = prefs.getString("git_name", "") ?: ""

    fun gitEmail(): String = prefs.getString("git_email", "") ?: ""

    fun gitUser(): String = prefs.getString("git_user", "") ?: ""

    fun gitToken(): String = prefs.getString("git_token", "") ?: ""

    fun gitSsh(): String = prefs.getString("git_ssh", "") ?: ""

    fun saveGit(name: String, email: String, user: String, token: String, ssh: String) {
        prefs.edit()
            .putString("git_name", name)
            .putString("git_email", email)
            .putString("git_user", user)
            .putString("git_token", token)
            .putString("git_ssh", ssh)
            .apply()
    }

    fun buildUrl(): String = prefs.getString("build_url", "") ?: ""

    fun buildToken(): String = prefs.getString("build_token", "") ?: ""

    fun saveBuild(url: String, token: String) {
        prefs.edit()
            .putString("build_url", url)
            .putString("build_token", token)
            .apply()
    }

    fun draftCommitMessage(): String = prefs.getString("draft_commit_msg", "") ?: ""

    fun saveDraftCommitMessage(text: String) {
        prefs.edit().putString("draft_commit_msg", text).apply()
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
