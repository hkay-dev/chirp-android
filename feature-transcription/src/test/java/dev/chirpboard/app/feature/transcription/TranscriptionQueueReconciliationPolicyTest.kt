package dev.chirpboard.app.feature.transcription

import dev.chirpboard.app.core.transcription.ManualRecoveryResult
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.UUID

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

    // --- PENDING_ENHANCEMENT reconciliation coverage ---
    // reconcilePendingQueueOwnership() now covers PENDING_ENHANCEMENT recordings.
    // The requeue decision uses the same shouldRequeuePending() predicate, so the
    // same ownership rules apply.

    @Test
    fun `startup recovers enhancing with missing ownership`() {
        assertTrue(
            shouldRecoverStaleEnhancing(
                trigger = ReconciliationTrigger.STARTUP,
                createdAtEpochMs = now,
                ownership = QueueOwnership.MISSING_OR_TERMINAL,
                nowEpochMs = now,
                staleThresholdMs = staleThresholdMs
            )
        )
    }

    @Test
    fun `startup does not recover enhancing with active ownership`() {
        assertFalse(
            shouldRecoverStaleEnhancing(
                trigger = ReconciliationTrigger.STARTUP,
                createdAtEpochMs = now,
                ownership = QueueOwnership.ACTIVE,
                nowEpochMs = now,
                staleThresholdMs = staleThresholdMs
            )
        )
    }

    @Test
    fun `startup does not recover enhancing when inspection timed out`() {
        assertFalse(
            shouldRecoverStaleEnhancing(
                trigger = ReconciliationTrigger.STARTUP,
                createdAtEpochMs = now,
                ownership = QueueOwnership.INSPECTION_TIMEOUT,
                nowEpochMs = now,
                staleThresholdMs = staleThresholdMs
            )
        )
    }

    @Test
    fun `pending enhancement is requeued only when ownership is missing or terminal`() {
        // shouldRequeuePending is status-agnostic; PENDING_ENHANCEMENT recordings
        // flow through the same path as PENDING_TRANSCRIPTION.
        assertTrue(shouldRequeuePending(QueueOwnership.MISSING_OR_TERMINAL))
        assertFalse(shouldRequeuePending(QueueOwnership.ACTIVE))
        assertFalse(shouldRequeuePending(QueueOwnership.INSPECTION_TIMEOUT))
    }

    @Test
    fun `queue work signature is empty only when all status sets are empty`() {
        assertTrue(
            QueueWorkSignature.of(emptyList(), emptyList(), emptyList(), emptyList()).isEmpty
        )
        assertFalse(
            QueueWorkSignature
                .of(listOf(recordingWithId(UUID.randomUUID())), emptyList(), emptyList(), emptyList())
                .isEmpty
        )
    }

    @Test
    fun `queue work signature changes when a recording transitions status`() {
        val id = UUID.randomUUID()
        val pending = QueueWorkSignature.of(listOf(recordingWithId(id)), emptyList(), emptyList(), emptyList())
        val transcribing = QueueWorkSignature.of(emptyList(), emptyList(), listOf(recordingWithId(id)), emptyList())

        // Same id, different status bucket -> a distinct signature so reconciliation re-runs.
        assertFalse(pending == transcribing)
    }

    @Test
    fun `queue work signature is stable for the same recording set`() {
        val id = UUID.randomUUID()
        val first = QueueWorkSignature.of(emptyList(), emptyList(), listOf(recordingWithId(id)), emptyList())
        val second = QueueWorkSignature.of(emptyList(), emptyList(), listOf(recordingWithId(id)), emptyList())

        // An unchanged TRANSCRIBING row produces an identical signature, so distinctUntilChanged
        // suppresses redundant passes; catching its staleness is the safety-net timer's job.
        assertEquals(first, second)
    }

    private fun recordingWithId(id: UUID): Recording =
        Recording(
            id = id,
            title = "r",
            audioPath = "/tmp/r.m4a",
            status = RecordingStatus.TRANSCRIBING,
            source = RecordingSource.APP,
            createdAt = Date(1_000L)
        )

    @Test
    fun `merge pending recordings keeps both statuses in newest first order`() {
        val pendingTranscription = Recording(
            id = UUID.randomUUID(),
            title = "pending transcription",
            audioPath = "/tmp/a.m4a",
            status = RecordingStatus.PENDING_TRANSCRIPTION,
            source = RecordingSource.APP,
            createdAt = Date(1_000L)
        )
        val pendingEnhancement = Recording(
            id = UUID.randomUUID(),
            title = "pending enhancement",
            audioPath = "/tmp/b.m4a",
            status = RecordingStatus.PENDING_ENHANCEMENT,
            source = RecordingSource.APP,
            createdAt = Date(2_000L)
        )

        val merged = mergePendingRecordings(
            pendingTranscription = listOf(pendingTranscription),
            pendingEnhancement = listOf(pendingEnhancement)
        )

        assertEquals(
            listOf(RecordingStatus.PENDING_ENHANCEMENT, RecordingStatus.PENDING_TRANSCRIPTION),
            merged.map { it.status }
        )
    }
}
