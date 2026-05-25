package dev.chirpboard.app.feature.llm.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredOutcomeExtractionParserTest {
    @Test
    fun `parseStructuredOutcomeExtractionResponse parses fenced json`() {
        val result =
            parseStructuredOutcomeExtractionResponse(
                """
                ```json
                {
                  "tasks": [" Review the draft ", "Review the draft"],
                  "decisions": ["Ship Friday"],
                  "followUps": ["Ping legal"]
                }
                ```
                """.trimIndent(),
            )

        assertTrue(result.isSuccess)
        val extraction = result.getOrThrow()
        assertEquals(listOf("Review the draft"), extraction.tasks)
        assertEquals(listOf("Ship Friday"), extraction.decisions)
        assertEquals(listOf("Ping legal"), extraction.followUps)
    }

    @Test
    fun `parseStructuredOutcomeExtractionResponse fails for invalid json`() {
        val result = parseStructuredOutcomeExtractionResponse("not json")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Couldn't parse structured outcome response") == true)
    }
}
