package com.example.andvibe

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SharedPreferences whose string values are AES-GCM sealed with an Android Keystore key.
 * Replaces EncryptedSharedPreferences (deprecated upstream) while keeping a sync prefs API.
 */
internal class KeystorePrefs private constructor(
    private val prefs: SharedPreferences,
    private val secret: SecretKey,
) : SharedPreferences by prefs {

    override fun getString(key: String?, defValue: String?): String? {
        val raw = prefs.getString(key, null) ?: return defValue
        return decrypt(raw) ?: defValue
    }

    override fun getAll(): MutableMap<String, *> {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in prefs.all) {
            out[k] = when (v) {
                is String -> decrypt(v) ?: v
                else -> v
            }
        }
        return out
    }

    override fun edit(): SharedPreferences.Editor = Editor(prefs.edit())

    private inner class Editor(private val inner: SharedPreferences.Editor) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key == null) return this
            if (value == null) inner.remove(key) else inner.putString(key, encrypt(value))
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            error("string sets are not supported")
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
            inner.putInt(key, value).let { this }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
            inner.putLong(key, value).let { this }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
            inner.putFloat(key, value).let { this }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            inner.putBoolean(key, value).let { this }

        override fun remove(key: String?): SharedPreferences.Editor =
            inner.remove(key).let { this }

        override fun clear(): SharedPreferences.Editor = inner.clear().let { this }

        override fun commit(): Boolean = inner.commit()

        override fun apply() = inner.apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        val iv = cipher.iv
        val bytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val packed = ByteBuffer.allocate(4 + iv.size + bytes.size)
            .putInt(iv.size)
            .put(iv)
            .put(bytes)
            .array()
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String? {
        return try {
            val packed = Base64.decode(blob, Base64.NO_WRAP)
            val buf = ByteBuffer.wrap(packed)
            val ivLen = buf.int
            val iv = ByteArray(ivLen)
            buf.get(iv)
            val cipherBytes = ByteArray(buf.remaining())
            buf.get(cipherBytes)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "andvibe_prefs_aes"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FILE = "andvibe_secrets_ks"

        fun open(context: Context): KeystorePrefs? {
            return try {
                val key = secretKey()
                val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                KeystorePrefs(prefs, key)
            } catch (_: Exception) {
                null
            }
        }

        private fun secretKey(): SecretKey {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .apply {
                    if (Build.VERSION.SDK_INT >= 28) {
                        setUnlockedDeviceRequired(false)
                    }
                }
                .build()
            gen.init(spec)
            return gen.generateKey()
        }
    }
}
