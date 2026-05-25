package dev.chirpboard.app.feature.recording.session

import dev.chirpboard.app.core.recording.RecordingOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class SessionRecoveryAssessorTest {
    @Test
    fun assess_playableActiveSegment_reportsNoLoss() {
        val sessionId = UUID.randomUUID()
        val activePath = createTempFile("active-playable", ".m4a")
        writePlayableStub(activePath)

        val entry =
            entry(
                sessionId = sessionId,
                audioPath = activePath.absolutePath,
                segmentPaths = emptyList(),
                lastHeartbeatEpochMs = STARTED_AT + TimeUnit.MINUTES.toMillis(4),
                activeSegmentStartedAtEpochMs = STARTED_AT + TimeUnit.MINUTES.toMillis(1),
            )

        val assessment = SessionRecoveryAssessor.assess(entry)

        assertFalse(assessment.hasPotentialLoss)
        assertEquals(0L, assessment.estimatedLostDurationMs)
    }

    @Test
    fun assess_incompleteActiveSegment_estimatesTailLoss() {
        val sessionId = UUID.randomUUID()
        val finalized = createTempFile("seg-000", ".m4a")
        val active = createTempFile("seg-001", ".m4a")
        writeRecoverableStub(finalized)
        writeRecoverableStub(active)

        val activeStarted = STARTED_AT + TimeUnit.MINUTES.toMillis(5)
        val heartbeat = activeStarted + TimeUnit.MINUTES.toMillis(3)

        val entry =
            entry(
                sessionId = sessionId,
                audioPath = active.absolutePath,
                segmentPaths = listOf(finalized.absolutePath),
                lastHeartbeatEpochMs = heartbeat,
                lastSegmentFinalizedAtEpochMs = activeStarted,
                activeSegmentStartedAtEpochMs = activeStarted,
            )

        val assessment = SessionRecoveryAssessor.assess(entry)

        assertTrue(assessment.hasPotentialLoss)
        assertTrue(assessment.estimatedLostDurationMs >= TimeUnit.MINUTES.toMillis(2))
        assertTrue(assessment.atRiskWindowMs >= TimeUnit.MINUTES.toMillis(3))
    }

    @Test
    fun assess_pausedSessionWithFinalizedActiveSegment_reportsNoLoss() {
        val sessionId = UUID.randomUUID()
        val segment = createTempFile("seg-paused", ".m4a")
        writeRecoverableStub(segment)

        val entry =
            entry(
                sessionId = sessionId,
                audioPath = segment.absolutePath,
                segmentPaths = listOf(segment.absolutePath),
                lastHeartbeatEpochMs = STARTED_AT + TimeUnit.MINUTES.toMillis(10),
                lastSegmentFinalizedAtEpochMs = STARTED_AT + TimeUnit.MINUTES.toMillis(10),
                activeSegmentStartedAtEpochMs = STARTED_AT,
            )

        val assessment = SessionRecoveryAssessor.assess(entry)

        assertFalse(assessment.hasPotentialLoss)
        assertEquals(0L, assessment.atRiskWindowMs)
    }

    private fun writeRecoverableStub(file: File) {
        file.writeBytes(stubM4aBytes(includeMoov = false))
    }

    private fun writePlayableStub(file: File) {
        file.writeBytes(stubM4aBytes(includeMoov = true))
    }

    private fun stubM4aBytes(includeMoov: Boolean): ByteArray {
        val payload =
            buildString {
                append("    ftypisom")
                if (includeMoov) {
                    append("    moov    ")
                }
                while (length < 2048) append('0')
            }
        return payload.toByteArray(Charsets.ISO_8859_1)
    }

    private fun entry(
        sessionId: UUID,
        audioPath: String,
        segmentPaths: List<String>,
        lastHeartbeatEpochMs: Long,
        lastSegmentFinalizedAtEpochMs: Long? = null,
        activeSegmentStartedAtEpochMs: Long = STARTED_AT,
    ): RecordingSessionEntry =
        RecordingSessionEntry(
            sessionId = sessionId,
            audioPath = audioPath,
            finalAudioPath = File(audioPath).parentFile?.parentFile?.let {
                File(it, "recording_export.m4a").absolutePath
            },
            segmentPaths = segmentPaths,
            origin = RecordingOrigin.APP,
            profileId = null,
            recordingId = UUID.randomUUID(),
            startedAtEpochMs = STARTED_AT,
            lastHeartbeatEpochMs = lastHeartbeatEpochMs,
            lastSegmentFinalizedAtEpochMs = lastSegmentFinalizedAtEpochMs,
            activeSegmentStartedAtEpochMs = activeSegmentStartedAtEpochMs,
            fileBytes = File(audioPath).length(),
            checkpointPath = null,
            state = SessionJournalState.ACTIVE,
            correlationId = "corr-test",
        )

    private fun createTempFile(prefix: String, suffix: String): File =
        File.createTempFile(prefix, suffix).also { it.deleteOnExit() }

    companion object {
        private val STARTED_AT = 1_700_000_000_000L
    }
}
