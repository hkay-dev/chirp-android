package dev.chirpboard.app.feature.transcription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionQueueReconciliationPolicyTest {

    private val now = 1_000_000L
    private val staleThresholdMs = 60_000L

    @Test
    fun `startup recovers transcribing with missing ownership`() {
        assertTrue(
            shouldRecoverStaleTranscribing(
                trigger = ReconciliationTrigger.STARTUP,
                createdAtEpochMs = now,
                ownership = QueueOwnership.MISSING_OR_TERMINAL,
                nowEpochMs = now,
                staleThresholdMs = staleThresholdMs
            )
        )
    }

    @Test
    fun `periodic does not recover fresh transcribing with missing ownership`() {
        assertFalse(
            shouldRecoverStaleTranscribing(
                trigger = ReconciliationTrigger.PERIODIC,
                createdAtEpochMs = now - 10_000L,
                ownership = QueueOwnership.MISSING_OR_TERMINAL,
                nowEpochMs = now,
                staleThresholdMs = staleThresholdMs
            )
        )
    }

    @Test
    fun `periodic recovers stale transcribing with missing ownership`() {
        assertTrue(
            shouldRecoverStaleTranscribing(
                trigger = ReconciliationTrigger.PERIODIC,
                createdAtEpochMs = now - 120_000L,
                ownership = QueueOwnership.MISSING_OR_TERMINAL,
                nowEpochMs = now,
                staleThresholdMs = staleThresholdMs
            )
        )
    }

    @Test
    fun `periodic does not recover transcribing with active ownership`() {
        assertFalse(
            shouldRecoverStaleTranscribing(
                trigger = ReconciliationTrigger.PERIODIC,
                createdAtEpochMs = now - 120_000L,
                ownership = QueueOwnership.ACTIVE,
                nowEpochMs = now,
                staleThresholdMs = staleThresholdMs
            )
        )
    }

    @Test
    fun `periodic does not recover transcribing when inspection timed out`() {
        assertFalse(
            shouldRecoverStaleTranscribing(
                trigger = ReconciliationTrigger.PERIODIC,
                createdAtEpochMs = now - 120_000L,
                ownership = QueueOwnership.INSPECTION_TIMEOUT,
                nowEpochMs = now,
                staleThresholdMs = staleThresholdMs
            )
        )
    }

    @Test
    fun `periodic recovers stale enhancing with missing ownership`() {
        assertTrue(
            shouldRecoverStaleEnhancing(
                trigger = ReconciliationTrigger.PERIODIC,
                createdAtEpochMs = now - 120_000L,
                ownership = QueueOwnership.MISSING_OR_TERMINAL,
                nowEpochMs = now,
                staleThresholdMs = staleThresholdMs
            )
        )
    }

    @Test
    fun `periodic does not recover fresh enhancing with missing ownership`() {
        assertFalse(
            shouldRecoverStaleEnhancing(
                trigger = ReconciliationTrigger.PERIODIC,
                createdAtEpochMs = now - 10_000L,
                ownership = QueueOwnership.MISSING_OR_TERMINAL,
                nowEpochMs = now,
                staleThresholdMs = staleThresholdMs
            )
        )
    }

    @Test
    fun `pending recordings are requeued only when ownership is missing or terminal`() {
        assertTrue(shouldRequeuePending(QueueOwnership.MISSING_OR_TERMINAL))
        assertFalse(shouldRequeuePending(QueueOwnership.ACTIVE))
        assertFalse(shouldRequeuePending(QueueOwnership.INSPECTION_TIMEOUT))
    }

    @Test
    fun `manual recovery is blocked while active work exists`() {
        assertEquals(
            ManualRecoveryResult.BLOCKED_ACTIVE_WORK,
            blockedManualRecoveryResult(QueueOwnership.ACTIVE)
        )
    }

    @Test
    fun `manual recovery is blocked when ownership check times out`() {
        assertEquals(
            ManualRecoveryResult.BLOCKED_OWNERSHIP_TIMEOUT,
            blockedManualRecoveryResult(QueueOwnership.INSPECTION_TIMEOUT)
        )
    }

    @Test
    fun `manual recovery is allowed when ownership is missing or terminal`() {
        assertNull(blockedManualRecoveryResult(QueueOwnership.MISSING_OR_TERMINAL))
    }
}
