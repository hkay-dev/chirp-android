package dev.chirpboard.app.feature.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionQueueSupportTest {

    @Test
    fun `parseRecoveryMetadata parses null or blank errorMessage`() {
        val result1 = parseRecoveryMetadata(null)
        assertNull(result1.reason)
        assertNull(result1.lastAttemptEpochMs)

        val result2 = parseRecoveryMetadata("   ")
        assertNull(result2.reason)
        assertNull(result2.lastAttemptEpochMs)
    }

    @Test
    fun `parseRecoveryMetadata strips normal prefixes`() {
        val result = parseRecoveryMetadata("${RECOVERABLE_QUEUE_HANDOFF_PREFIX}Some error")
        assertEquals("Some error", result.reason)
        assertNull(result.lastAttemptEpochMs)
    }

    @Test
    fun `parseRecoveryMetadata extracts manual recovery with timestamp`() {
        val timestamp = 1680000000000L
        val errorMsg = "${MANUAL_RECOVERY_PREFIX}Failed to start|attemptAt=$timestamp"
        val result = parseRecoveryMetadata(errorMsg)
        
        assertEquals("Failed to start", result.reason)
        assertEquals(timestamp, result.lastAttemptEpochMs)
    }

    @Test
    fun `parseRecoveryMetadata handles manual recovery without valid timestamp`() {
        val errorMsg = "${MANUAL_RECOVERY_PREFIX}Failed to start"
        val result = parseRecoveryMetadata(errorMsg)
        
        assertEquals("Failed to start", result.reason)
        assertNull(result.lastAttemptEpochMs)
    }

    @Test
    fun `buildManualRecoveryMessage includes prefix and current timestamp`() {
        val start = System.currentTimeMillis()
        val result = buildManualRecoveryMessage("Test reason")
        val end = System.currentTimeMillis()

        assertTrue(result.startsWith("${MANUAL_RECOVERY_PREFIX}Test reason|attemptAt="))
        val timestampStr = result.substringAfter("|attemptAt=")
        val timestamp = timestampStr.toLongOrNull()
        
        assertTrue(timestamp != null && timestamp in start..end)
    }
}
