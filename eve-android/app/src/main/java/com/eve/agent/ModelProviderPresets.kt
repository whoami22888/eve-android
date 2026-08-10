package com.eve.agent

/** Built-in provider presets. Model IDs are editable because providers add/remove models over time. */
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
        Preset("openai", "OpenAI", "https://api.openai.com", listOf("gpt-5", "gpt-5-mini", "gpt-4.1")),
        Preset("anthropic", "Anthropic / Claude", "https://api.anthropic.com", listOf("claude-sonnet-4-5", "claude-opus-4-1", "claude-haiku-3-5")),
        Preset("gemini", "Google Gemini", "https://generativelanguage.googleapis.com", listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite")),
        Preset("deepseek", "DeepSeek", "https://api.deepseek.com", listOf("deepseek-chat", "deepseek-reasoner")),
        Preset("openrouter", "OpenRouter", "https://openrouter.ai/api", listOf("openai/gpt-5", "anthropic/claude-sonnet-4.5", "google/gemini-2.5-pro", "deepseek/deepseek-chat"))
    )

    val LOCAL = listOf(
        Preset("ollama", "Ollama (local)", "http://127.0.0.1:11434", listOf("qwen3", "deepseek-coder", "gemma3"), local = true, apiKeyRequired = false)
    )

    val ALL = CLOUD + LOCAL
    fun find(id: String): Preset? = ALL.firstOrNull { it.id == id }
}
