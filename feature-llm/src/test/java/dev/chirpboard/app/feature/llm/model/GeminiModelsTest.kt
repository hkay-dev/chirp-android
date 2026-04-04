package dev.chirpboard.app.feature.llm.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeminiModelsTest {
    @Test
    fun `GeminiRequest of creates valid structure`() {
        val text = "test request"
        val request = GeminiRequest.of(text)
        assertEquals(1, request.contents.size)
        assertEquals(1, request.contents[0].parts.size)
        assertEquals(text, request.contents[0].parts[0].text)
    }

    @Test
    fun `extractText returns null when candidates is null`() {
        val response = GeminiResponse(candidates = null, error = null)
        assertNull(response.extractText())
    }

    @Test
    fun `extractText returns text when present`() {
        val expected = "response text"
        val response = GeminiResponse(
            candidates = listOf(
                GeminiResponse.Candidate(
                    content = GeminiResponse.Content(
                        parts = listOf(
                            GeminiResponse.Part(text = expected)
                        )
                    )
                )
            ),
            error = null
        )
        assertEquals(expected, response.extractText())
    }

    @Test
    fun `extractText returns null for empty candidates list`() {
        val response = GeminiResponse(candidates = emptyList(), error = null)
        assertNull(response.extractText())
    }
}
