package com.example.andvibe

/**
 * Write-only secret fields: never put stored plaintext back into the UI.
 * Blank input keeps the existing value; non-blank replaces it.
 */
object SecretFields {
    const val SAVED_HINT = "Saved — enter new key to replace"

    fun nextSecret(typed: String, existing: String): String =
        typed.trim().ifBlank { existing }

    fun hint(emptyHint: String, hasStored: Boolean): String =
        if (hasStored) SAVED_HINT else emptyHint
}
