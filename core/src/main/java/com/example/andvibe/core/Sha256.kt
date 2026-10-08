package com.example.andvibe.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Sha256 {
    fun hex(file: File): String = file.inputStream().use { hex(it) }

    fun hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { b -> "%02x".format(b) }
    }

    fun matches(file: File, expected: String): Boolean {
        if (expected.isBlank()) return true
        return hex(file).equals(expected.trim(), ignoreCase = true)
    }
}
