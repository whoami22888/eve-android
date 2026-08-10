package com.eve.agent

/** Built-in provider presets. EVE uses model=auto to route each agent stage automatically. */
object ModelProviderPresets {
    data class Preset(
        val id: String,
        val name: String,
        val baseUrl: String,
        val models: List<String>,
        val local: Boolean = false,
        val apiKeyRequired: Boolean = true
    )

    val CLOUD = listOf(
        Preset("openai", "OpenAI", "https://api.openai.com", listOf("auto", "gpt-5.1", "gpt-5-mini", "gpt-5")),
        Preset("anthropic", "Anthropic / Claude", "https://api.anthropic.com", listOf("auto", "claude-opus-4-1", "claude-sonnet-4-5", "claude-haiku-3-5")),
        Preset("gemini", "Google Gemini", "https://generativelanguage.googleapis.com", listOf("auto", "gemini-3.1-pro-preview", "gemini-3.6-flash", "gemini-3.5-flash-lite")),
        Preset("deepseek", "DeepSeek V4", "https://api.deepseek.com", listOf("auto", "deepseek-v4-pro", "deepseek-v4-flash")),
        Preset("openrouter", "OpenRouter", "https://openrouter.ai/api", listOf("auto", "deepseek/deepseek-v4-pro", "google/gemini-3.6-flash", "anthropic/claude-sonnet-4-5"))
    )

    val LOCAL = listOf(
        Preset("ollama", "Ollama (local)", "http://127.0.0.1:11434", listOf("auto", "deepseek-v4-flash", "qwen3", "gemma3"), local = true, apiKeyRequired = false)
    )

    val ALL = CLOUD + LOCAL
    fun find(id: String): Preset? = ALL.firstOrNull { it.id == id }
}
