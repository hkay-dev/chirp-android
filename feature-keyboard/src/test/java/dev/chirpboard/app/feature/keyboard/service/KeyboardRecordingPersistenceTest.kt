package dev.chirpboard.app.feature.keyboard.service

import dev.chirpboard.app.data.model.RecordingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardRecordingPersistenceTest {
    @Test
    fun `buildKeyboardPersistencePlan uses rawText for title`() {
        val plan = buildKeyboardPersistencePlan("Hello world", null, null)
        assertEquals("Hello world", plan.title)
        assertEquals(RecordingStatus.COMPLETED, plan.status)
        assertEquals("Hello world", plan.rawText)
        assertNull(plan.processedText)
        assertNull(plan.errorMessage)
    }

    @Test
    fun `buildKeyboardPersistencePlan handles empty rawText`() {
        val plan = buildKeyboardPersistencePlan("   ", null, null)
        assertEquals("Keyboard recording", plan.title)
        assertNull(plan.rawText)
    }

    @Test
    fun `buildKeyboardPersistencePlan uses errorMessage for title if rawText empty`() {
        val plan = buildKeyboardPersistencePlan("   ", null, "Error happened")
        assertEquals("Error happened", plan.title)
        assertEquals(RecordingStatus.FAILED, plan.status)
        assertNull(plan.rawText)
        assertEquals("Error happened", plan.errorMessage)
    }
}
