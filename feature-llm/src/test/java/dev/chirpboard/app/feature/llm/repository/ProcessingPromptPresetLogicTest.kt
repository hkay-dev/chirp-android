package dev.chirpboard.app.feature.llm.repository

import dev.chirpboard.app.feature.llm.model.ProcessingMode
import dev.chirpboard.app.feature.llm.model.ProcessingModeDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingPromptPresetLogicTest {
    @Test
    fun builtInDefaults_includeEditableModes() {
        assertTrue(ProcessingModeDefaults.editableBuiltInIds.contains("proofread"))
        assertFalse(ProcessingModeDefaults.editableBuiltInIds.contains("smart"))
    }

    @Test
    fun defaultPrompt_returnsProofreadPrompt() {
        val prompt = ProcessingModeDefaults.defaultPrompt("proofread")
        assertEquals(ProcessingMode.Proofread.prompt, prompt)
        assertTrue(prompt!!.contains("POST-PROCESSING ENGINE"))
    }

    @Test
    fun displayName_mapsKnownModes() {
        assertEquals("Proofread", ProcessingModeDefaults.displayName("proofread"))
        assertEquals("Smart", ProcessingModeDefaults.displayName("smart"))
    }
}
