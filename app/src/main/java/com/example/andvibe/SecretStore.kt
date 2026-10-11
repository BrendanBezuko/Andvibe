package com.example.andvibe

import android.content.Context
import android.content.SharedPreferences

class SecretStore(context: Context) {
    val plain: Boolean
    private val prefs: SharedPreferences

    init {
        val app = context.applicationContext
        val keystore = KeystorePrefs.open(app)
        if (keystore != null) {
            migrateFromEncryptedPrefs(app, keystore)
            prefs = keystore
            plain = false
        } else {
            prefs = app.getSharedPreferences("andvibe_secrets_plain", Context.MODE_PRIVATE)
            plain = true
        }
    }

    fun get(provider: Provider, field: String, default: String): String {
        if (provider == Provider.CUSTOM) {
            migrateLegacyCustom()
            val active = activeCustomServer()
            return when (field) {
                "key" -> active?.key ?: default
                "model" -> active?.model?.ifBlank { default } ?: default
                "base" -> active?.base?.ifBlank { default } ?: default
                "style" -> active?.style?.id ?: default
                else -> {
                    val key = "${provider.id}_$field"
                    if (prefs.contains(key)) prefs.getString(key, default) ?: default else default
                }
            }
        }
        val key = "${provider.id}_$field"
        return if (prefs.contains(key)) prefs.getString(key, default) ?: default else default
    }

    fun apiStyle(provider: Provider): ApiStyle {
        if (provider == Provider.CUSTOM) {
            migrateLegacyCustom()
            return activeCustomServer()?.style ?: ApiStyle.OPENAI
        }
        return ApiStyle.from(provider)
    }

    fun save(provider: Provider, apiKey: String, model: String, base: String) {
        saveProvider(provider, apiKey, model, base, select = true)
    }

    fun saveChoice(provider: Provider, model: String, base: String) {
        if (provider == Provider.CUSTOM) {
            migrateLegacyCustom()
            val active = activeCustomServer()
            if (active != null) {
                upsertCustomServer(active.copy(model = model, base = base))
            } else {
                val created = CustomServer.new(model = model, base = base)
                saveCustomServers(listOf(created), selectedId = created.id)
            }
            prefs.edit().putString("provider", provider.id).apply()
            return
        }
        prefs.edit()
            .putString("${provider.id}_model", model)
            .putString("${provider.id}_base", base)
            .putString("provider", provider.id)
            .apply()
    }

    fun saveKey(provider: Provider, apiKey: String) {
        if (provider == Provider.CUSTOM) {
            migrateLegacyCustom()
            val active = activeCustomServer()
            if (active != null) {
                upsertCustomServer(active.copy(key = apiKey))
            } else {
                val created = CustomServer.new(key = apiKey)
                saveCustomServers(listOf(created), selectedId = created.id)
            }
            return
        }
        prefs.edit().putString("${provider.id}_key", apiKey).commit()
    }

    fun saveProvider(provider: Provider, apiKey: String, model: String, base: String, select: Boolean) {
        if (provider == Provider.CUSTOM) {
            migrateLegacyCustom()
            val active = activeCustomServer()
            if (active != null) {
                upsertCustomServer(active.copy(key = apiKey, model = model, base = base))
            } else {
                val created = CustomServer.new(key = apiKey, model = model, base = base)
                saveCustomServers(listOf(created), selectedId = created.id)
            }
            if (select) prefs.edit().putString("provider", provider.id).apply()
            return
        }
        val edit = prefs.edit()
            .putString("${provider.id}_key", apiKey)
            .putString("${provider.id}_model", model)
            .putString("${provider.id}_base", base)
        if (select) edit.putString("provider", provider.id)
        edit.apply()
    }

    fun customServers(): List<CustomServer> {
        migrateLegacyCustom()
        return CustomServer.listFromJson(prefs.getString(CUSTOM_SERVERS, null))
    }

    fun selectedCustomId(): String {
        migrateLegacyCustom()
        return prefs.getString(CUSTOM_SELECTED, "") ?: ""
    }

    fun activeCustomServer(): CustomServer? {
        val servers = customServers()
        if (servers.isEmpty()) return null
        val selected = selectedCustomId()
        return servers.firstOrNull { it.id == selected } ?: servers.first()
    }

    fun selectCustomServer(id: String) {
        migrateLegacyCustom()
        val servers = customServers()
        if (servers.none { it.id == id }) return
        prefs.edit().putString(CUSTOM_SELECTED, id).apply()
    }

    fun saveCustomServers(servers: List<CustomServer>, selectedId: String? = null) {
        val selected = selectedId
            ?: selectedCustomId().takeIf { id -> servers.any { it.id == id } }
            ?: servers.firstOrNull()?.id
            ?: ""
        prefs.edit()
            .putString(CUSTOM_SERVERS, CustomServer.listToJson(servers))
            .putString(CUSTOM_SELECTED, selected)
            .apply()
        // Keep legacy keys in sync for older console vault rows / debugging.
        val active = servers.firstOrNull { it.id == selected } ?: servers.firstOrNull()
        if (active != null) {
            prefs.edit()
                .putString("custom_key", active.key)
                .putString("custom_model", active.model)
                .putString("custom_base", active.base)
                .putString("custom_style", active.style.id)
                .apply()
        }
    }

    fun upsertCustomServer(server: CustomServer, select: Boolean = true) {
        migrateLegacyCustom()
        val servers = customServers().toMutableList()
        val index = servers.indexOfFirst { it.id == server.id }
        if (index >= 0) servers[index] = server else servers.add(server)
        saveCustomServers(servers, selectedId = if (select) server.id else selectedCustomId())
    }

    fun deleteCustomServer(id: String): Boolean {
        migrateLegacyCustom()
        val servers = customServers()
        if (servers.size <= 1) return false
        val next = servers.filterNot { it.id == id }
        if (next.size == servers.size) return false
        val selected = if (selectedCustomId() == id) next.first().id else selectedCustomId()
        saveCustomServers(next, selectedId = selected)
        return true
    }

    fun ensureCustomServer(): CustomServer {
        migrateLegacyCustom()
        activeCustomServer()?.let { return it }
        val created = CustomServer.new(name = "Custom 1")
        saveCustomServers(listOf(created), selectedId = created.id)
        return created
    }

    /**
     * One-time lift of flat custom_key/model/base (+ optional style) into the servers list.
     */
    private fun migrateLegacyCustom() {
        if (prefs.getBoolean(CUSTOM_MIGRATED, false)) return
        val existing = CustomServer.listFromJson(prefs.getString(CUSTOM_SERVERS, null))
        if (existing.isNotEmpty()) {
            prefs.edit().putBoolean(CUSTOM_MIGRATED, true).apply()
            return
        }
        val key = prefs.getString("custom_key", "") ?: ""
        val model = prefs.getString("custom_model", "") ?: ""
        val base = prefs.getString("custom_base", "") ?: ""
        val style = ApiStyle.fromId(prefs.getString("custom_style", null))
        val server = if (key.isNotBlank() || model.isNotBlank() || base.isNotBlank()) {
            CustomServer.new(name = "Custom 1", key = key, model = model, base = base, style = style)
        } else {
            CustomServer.new(name = "Custom 1")
        }
        prefs.edit()
            .putString(CUSTOM_SERVERS, CustomServer.listToJson(listOf(server)))
            .putString(CUSTOM_SELECTED, server.id)
            .putBoolean(CUSTOM_MIGRATED, true)
            .apply()
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

    /** When true, Gradle builds skip the local toolchain and use the remote builder. */
    fun preferRemoteBuild(): Boolean = prefs.getBoolean("prefer_remote_build", false)

    fun setPreferRemoteBuild(value: Boolean) {
        prefs.edit().putBoolean("prefer_remote_build", value).apply()
    }

    fun draftCommitMessage(): String = prefs.getString("draft_commit_msg", "") ?: ""

    fun saveDraftCommitMessage(text: String) {
        prefs.edit().putString("draft_commit_msg", text).apply()
    }

    companion object {
        private const val MIGRATED = "__migrated_from_esp"
        private const val CUSTOM_SERVERS = "custom_servers"
        private const val CUSTOM_SELECTED = "custom_selected"
        private const val CUSTOM_MIGRATED = "custom_servers_migrated"

        /**
         * One-time copy from the deprecated EncryptedSharedPreferences file into KeystorePrefs.
         * Uses the old API only as a migration reader; new writes never go there.
         */
        @Suppress("DEPRECATION")
        private fun migrateFromEncryptedPrefs(context: Context, dest: SharedPreferences) {
            if (dest.getBoolean(MIGRATED, false)) return
            val esp = openLegacyEncrypted(context) ?: run {
                dest.edit().putBoolean(MIGRATED, true).apply()
                return
            }
            if (esp.all.isEmpty()) {
                dest.edit().putBoolean(MIGRATED, true).apply()
                return
            }
            val edit = dest.edit()
            for ((key, value) in esp.all) {
                when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                }
            }
            edit.putBoolean(MIGRATED, true)
            if (edit.commit()) {
                esp.edit().clear().apply()
            }
        }

        @Suppress("DEPRECATION")
        private fun openLegacyEncrypted(context: Context): SharedPreferences? {
            return try {
                val alias = androidx.security.crypto.MasterKeys.getOrCreate(
                    androidx.security.crypto.MasterKeys.AES256_GCM_SPEC,
                )
                androidx.security.crypto.EncryptedSharedPreferences.create(
                    "andvibe_secrets",
                    alias,
                    context,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
