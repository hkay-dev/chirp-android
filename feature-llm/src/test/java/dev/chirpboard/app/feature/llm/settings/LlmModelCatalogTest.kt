package dev.chirpboard.app.feature.llm.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmModelCatalogTest {
    @Test
    fun `resolveModelId uses stable default when unset`() {
        assertEquals(DEFAULT_GEMINI_MODEL, resolveModelId(LlmProvider.GEMINI, null))
        assertEquals(defaultModelFor(LlmProvider.OPENAI), resolveModelId(LlmProvider.OPENAI, "   "))
    }

    @Test
    fun `resolveModelId migrates deprecated Gemini preview ids`() {
        assertEquals(
            DEFAULT_GEMINI_MODEL,
            resolveModelId(LlmProvider.GEMINI, "gemini-3.1-flash-lite-preview"),
        )
    }

    @Test
    fun `resolveModelId keeps known model ids`() {
        assertEquals("claude-sonnet-4-6", resolveModelId(LlmProvider.ANTHROPIC, "claude-sonnet-4-6"))
    }

    @Test
    fun `modelsFor returns options for each provider`() {
        LlmProvider.entries.forEach { provider ->
            assert(modelsFor(provider).isNotEmpty())
        }
    }
}
