package dev.chirpboard.app.feature.recording.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRecordEntryAffordanceStateTest {

    @Test
    fun recordEntryActionEnabled_isFalseWhenChecking() {
        assertFalse(isRecordEntryActionEnabled(isChecking = true))
        assertTrue(isRecordEntryActionEnabled(isChecking = false))
    }

    @Test
    fun recordFabLabel_matchesCheckingState() {
        assertEquals("Checking...", recordFabLabel(isChecking = true))
        assertEquals("Record", recordFabLabel(isChecking = false))
    }

    @Test
    fun emptyStateButtonLabel_matchesCheckingState() {
        assertEquals("Checking model...", emptyStateRecordButtonLabel(isChecking = true))
        assertEquals("Record now", emptyStateRecordButtonLabel(isChecking = false))
    }
}
