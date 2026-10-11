package com.example.andvibe

import android.content.SharedPreferences

/** Minimal string key-value storage, so features don't need a Context just for prefs. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

/** In-memory store for JVM tests. */
class MemoryKeyValueStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    val values = initial.toMutableMap()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String) {
        values[key] = value
    }
}
