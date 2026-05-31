package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.recording.RecordingOrigin
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingStopHandoffTest {
    @Test
    fun `marks stopping only after capture stop completes`() =
        runTest {
            val recordingId = UUID.randomUUID()
            val sessionId = UUID.randomUUID()
            val events = mutableListOf<String>()

            RecordingStopHandoff.handoff(
                snapshot = snapshot(recordingId),
                sessionId = sessionId,
                stopCapture = {
                    events += "capture-stop-start"
                    events += "capture-stop-complete"
                },
                markAbandoned = { _, _ -> events += "abandoned" },
                markStopping = { id -> events += "stopping:$id" },
                enqueueFinalize = { _, id -> events += "finalize:$id" },
                onCaptureStopHandoff = { id -> events += "handoff:$id" },
            )

            assertEquals(
                listOf(
                    "capture-stop-start",
                    "capture-stop-complete",
                    "stopping:$sessionId",
                    "finalize:$sessionId",
                    "handoff:$recordingId",
                ),
                events,
            )
        }

    @Test
    fun `missing recording id abandons after capture stop without stopping marker`() =
        runTest {
            val sessionId = UUID.randomUUID()
            val events = mutableListOf<String>()

            RecordingStopHandoff.handoff(
                snapshot = snapshot(recordingId = null),
                sessionId = sessionId,
                stopCapture = { events += "capture-stop-complete" },
                markAbandoned = { id, recordingId -> events += "abandoned:$id:$recordingId" },
                markStopping = { events += "stopping:$it" },
                enqueueFinalize = { _, _ -> events += "finalize" },
                onCaptureStopHandoff = { id -> events += "handoff:$id" },
            )

            assertEquals(
                listOf(
                    "capture-stop-complete",
                    "abandoned:$sessionId:null",
                    "handoff:null",
                ),
                events,
            )
        }

    private fun snapshot(recordingId: UUID?): StopSnapshot =
        StopSnapshot(
            origin = RecordingOrigin.APP,
            profileId = null,
            recordingId = recordingId,
            audioFilePath = "/tmp/test.m4a",
            durationMs = 1000L,
            stoppedAtEpochMs = 0L,
            wasPaused = false,
            correlationId = "corr",
        )
}
