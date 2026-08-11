package dev.chirpboard.app.feature.llm.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingModeTest {
    @Test
    fun `fromId returns correct preset modes`() {
        assertEquals(ProcessingMode.Proofread, ProcessingMode.fromId("proofread"))
        assertEquals(ProcessingMode.Formal, ProcessingMode.fromId("formal"))
        assertEquals(ProcessingMode.Casual, ProcessingMode.fromId("casual"))
        assertEquals(ProcessingMode.Email, ProcessingMode.fromId("email"))
        assertEquals(ProcessingMode.Code, ProcessingMode.fromId("code"))
        assertEquals(ProcessingMode.Smart, ProcessingMode.fromId("smart"))
    }

    @Test
    fun `fromId maps raw to Proofread for backward compatibility`() {
        assertEquals(ProcessingMode.Proofread, ProcessingMode.fromId("raw"))
    }

    @Test
    fun `fromId defaults to Proofread for unknown id`() {
        assertEquals(ProcessingMode.Proofread, ProcessingMode.fromId("unknown_id"))
    }

    @Test
    fun `fromId maps the legacy custom id to an empty Custom mode`() {
        val mode = ProcessingMode.fromId("custom")

        assertTrue(mode is ProcessingMode.Custom)
        assertEquals("", (mode as ProcessingMode.Custom).customPrompt)
    }
}
