package dev.chirpboard.app.feature.transcription

import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.UUID

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

    @Test
    fun `shouldRecoverStaleTranscribing returns true on STARTUP if missing ownership`() {
        val result = shouldRecoverStaleTranscribing(
            trigger = ReconciliationTrigger.STARTUP,
            createdAtEpochMs = 1000L,
            ownership = QueueOwnership.MISSING_OR_TERMINAL,
            nowEpochMs = 2000L,
            staleThresholdMs = 5000L // Not stale yet!
        )
        assertTrue(result) // But startup overrides
    }

    @Test
    fun `shouldRecoverStaleTranscribing returns true on PERIODIC if stale and missing ownership`() {
        val result = shouldRecoverStaleTranscribing(
            trigger = ReconciliationTrigger.PERIODIC,
            createdAtEpochMs = 1000L,
            ownership = QueueOwnership.MISSING_OR_TERMINAL,
            nowEpochMs = 7000L, // Difference is 6000L
            staleThresholdMs = 5000L
        )
        assertTrue(result)
    }

    @Test
    fun `shouldRecoverStaleTranscribing returns false if not stale on PERIODIC`() {
        val result = shouldRecoverStaleTranscribing(
            trigger = ReconciliationTrigger.PERIODIC,
            createdAtEpochMs = 1000L,
            ownership = QueueOwnership.MISSING_OR_TERMINAL,
            nowEpochMs = 3000L, // Difference is 2000L
            staleThresholdMs = 5000L
        )
        assertFalse(result)
    }

    @Test
    fun `shouldRecoverStaleTranscribing returns false if active regardless of time`() {
        val result = shouldRecoverStaleTranscribing(
            trigger = ReconciliationTrigger.STARTUP,
            createdAtEpochMs = 1000L,
            ownership = QueueOwnership.ACTIVE,
            nowEpochMs = 10000L,
            staleThresholdMs = 500L
        )
        assertFalse(result)
    }

    @Test
    fun `mergePendingRecordings sorts descending by creation time`() {
        val oldRecord = Recording(
            id = UUID.randomUUID(),
            audioPath = "path1", title = "1", source = dev.chirpboard.app.data.model.RecordingSource.APP,
            createdAt = Date(1000),
            status = RecordingStatus.PENDING_TRANSCRIPTION,
            durationMs = 0
        )
        val newRecord = Recording(
            id = UUID.randomUUID(),
            audioPath = "path2", title = "2", source = dev.chirpboard.app.data.model.RecordingSource.APP,
            createdAt = Date(3000),
            status = RecordingStatus.PENDING_ENHANCEMENT,
            durationMs = 0
        )
        val middleRecord = Recording(
            id = UUID.randomUUID(),
            audioPath = "path3", title = "3", source = dev.chirpboard.app.data.model.RecordingSource.APP,
            createdAt = Date(2000),
            status = RecordingStatus.PENDING_TRANSCRIPTION,
            durationMs = 0
        )
        
        val merged = mergePendingRecordings(
            listOf(oldRecord, middleRecord),
            listOf(newRecord)
        )
        
        assertEquals(3, merged.size)
        assertEquals(newRecord.id, merged[0].id)
        assertEquals(middleRecord.id, merged[1].id)
        assertEquals(oldRecord.id, merged[2].id)
    }
}
