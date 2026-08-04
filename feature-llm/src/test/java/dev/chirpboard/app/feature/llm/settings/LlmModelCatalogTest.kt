package dev.chirpboard.app.feature.llm.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmModelCatalogTest {
    @Test
    fun `resolveModelId uses stable default when unset`() {
        assertEquals("gemini-3.5-flash-lite", DEFAULT_GEMINI_MODEL)
        assertEquals(DEFAULT_GEMINI_MODEL, resolveModelId(LlmProvider.GEMINI, null))
        assertEquals(DEFAULT_GEMINI_MODEL, defaultModelFor(LlmProvider.GEMINI))
        assertEquals(defaultModelFor(LlmProvider.OPENAI), resolveModelId(LlmProvider.OPENAI, "   "))
    }

    @Test
    fun `resolveModelId migrates deprecated Gemini preview ids`() {
        assertEquals(
            "gemini-3.1-flash-lite",
            resolveModelId(LlmProvider.GEMINI, "gemini-3.1-flash-lite-preview"),
        )
    }

    @Test
    fun `resolveModelId keeps known model ids`() {
        assertEquals("claude-sonnet-4-6", resolveModelId(LlmProvider.ANTHROPIC, "claude-sonnet-4-6"))
    }

    @Test
    fun `Gemini catalog includes newly available Flash models`() {
        val models = modelsFor(LlmProvider.GEMINI)

        assertEquals(
            "Gemini 3.5 Flash-Lite",
            models.single { it.id == "gemini-3.5-flash-lite" }.label,
        )
        assertEquals(
            "Gemini 3.6 Flash",
            models.single { it.id == "gemini-3.6-flash" }.label,
        )
    }

    @Test
    fun `modelsFor returns options for each provider`() {
        LlmProvider.entries.forEach { provider ->
            assert(modelsFor(provider).isNotEmpty())
        }
    }
}
