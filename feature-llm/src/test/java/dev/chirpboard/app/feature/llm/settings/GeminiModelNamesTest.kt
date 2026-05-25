package dev.chirpboard.app.feature.llm.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiModelNamesTest {
    @Test
    fun `resolveGeminiModelName uses stable default when unset`() {
        assertEquals(DEFAULT_GEMINI_MODEL, resolveGeminiModelName(null))
        assertEquals(DEFAULT_GEMINI_MODEL, resolveGeminiModelName("   "))
    }

    @Test
    fun `resolveGeminiModelName migrates deprecated preview ids`() {
        assertEquals(
            DEFAULT_GEMINI_MODEL,
            resolveGeminiModelName("gemini-3.1-flash-lite-preview"),
        )
        assertEquals(
            "gemini-3.5-flash",
            resolveGeminiModelName("gemini-3-flash-preview"),
        )
    }

    @Test
    fun `resolveGeminiModelName keeps current stable ids`() {
        assertEquals("gemini-2.5-flash-lite", resolveGeminiModelName("gemini-2.5-flash-lite"))
    }
}
