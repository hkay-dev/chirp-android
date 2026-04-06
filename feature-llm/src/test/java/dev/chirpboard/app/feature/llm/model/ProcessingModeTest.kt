package dev.chirpboard.app.feature.llm.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `fromId returns Custom mode with provided prompt`() {
        val customPrompt = "Make it sound like Shakespeare"
        val mode = ProcessingMode.fromId("custom", customPrompt)
        
        assertTrue(mode is ProcessingMode.Custom)
        assertEquals(customPrompt, (mode as ProcessingMode.Custom).customPrompt)
        assertEquals(customPrompt, mode.prompt)
    }

    @Test
    fun `fromId returns Custom mode with empty prompt if not provided`() {
        val mode = ProcessingMode.fromId("custom", null)
        
        assertTrue(mode is ProcessingMode.Custom)
        assertEquals("", (mode as ProcessingMode.Custom).customPrompt)
    }

    @Test
    fun `Smart mode has null prompt`() {
        assertNull(ProcessingMode.Smart.prompt)
    }

    @Test
    fun `presets list contains expected modes`() {
        val expectedPresets = listOf(
            ProcessingMode.Smart,
            ProcessingMode.Proofread,
            ProcessingMode.Formal,
            ProcessingMode.Casual,
            ProcessingMode.Email,
            ProcessingMode.Code
        )
        assertEquals(expectedPresets, ProcessingMode.presets)
    }
}
