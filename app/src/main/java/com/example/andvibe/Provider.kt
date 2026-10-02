package com.example.andvibe

enum class Provider(
    val id: String,
    val label: String,
    val defaultModel: String,
    val defaultBase: String
) {
    OPENAI("openai", "OpenAI", "gpt-6.1-sol", "https://api.openai.com/v1"),
    ANTHROPIC("anthropic", "Anthropic", "claude-sonnet-5", "https://api.anthropic.com"),
    GEMINI("gemini", "Gemini", "gemini-3.8-flash", "https://generativelanguage.googleapis.com/v1beta"),
    GROK("grok", "Grok", "grok-4.6", "https://api.x.ai/v1"),
    OPENROUTER("openrouter", "OpenRouter", "anthropic/claude-sonnet-5", "https://openrouter.ai/api/v1"),
    CURSOR("cursor", "Cursor", "composer-2.5", "https://api.cursor.com"),
    CUSTOM("custom", "Custom", "", "")
}
