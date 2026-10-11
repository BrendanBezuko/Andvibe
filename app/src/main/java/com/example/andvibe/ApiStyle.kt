package com.example.andvibe

/** Wire format for a custom (or built-in) chat endpoint. */
enum class ApiStyle(val id: String, val label: String) {
    OPENAI("openai", "OpenAI-compatible"),
    ANTHROPIC("anthropic", "Anthropic Messages");

    companion object {
        fun fromId(id: String?): ApiStyle =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: OPENAI

        fun from(provider: Provider): ApiStyle = when (provider) {
            Provider.ANTHROPIC -> ANTHROPIC
            else -> OPENAI
        }
    }
}
