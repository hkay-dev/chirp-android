package dev.chirpboard.app.feature.llm.settings

import androidx.annotation.DrawableRes
import dev.chirpboard.app.feature.llm.R

enum class LlmProvider(
    val id: String,
    val displayName: String,
    val apiKeyHelpUrl: String,
    @DrawableRes val iconRes: Int,
) {
    GEMINI("gemini", "Google Gemini", "https://aistudio.google.com/apikey", R.drawable.llm_provider_google),
    OPENAI("openai", "OpenAI", "https://platform.openai.com/api-keys", R.drawable.llm_provider_openai),
    ANTHROPIC("anthropic", "Anthropic", "https://console.anthropic.com/settings/keys", R.drawable.llm_provider_anthropic),
    GROQ("groq", "Groq", "https://console.groq.com/keys", R.drawable.llm_provider_groq),
    CEREBRAS("cerebras", "Cerebras", "https://cloud.cerebras.ai", R.drawable.llm_provider_cerebras),
    ;

    companion object {
        fun fromId(id: String?): LlmProvider =
            entries.firstOrNull { it.id == id } ?: GEMINI
    }
}

data class LlmModelOption(
    val id: String,
    val label: String,
)
