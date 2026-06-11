package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.recording.RecordingOrigin
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingStopHandoffTest {
    @Test
    fun `marks stopping only after capture stop completes`() =
        runTest {
            val recordingId = UUID.randomUUID()
            val sessionId = UUID.randomUUID()
            val stopGeneration = AtomicInteger(1)
            val events = mutableListOf<String>()

            RecordingStopHandoff.handoff(
                sessionId = sessionId,
                generation = 1,
                stopGeneration = stopGeneration,
                stopCapture = {
                    events += "capture-stop-start"
                    events += "capture-stop-complete"
                },
                captureSnapshot = { snapshot(recordingId) },
                markAbandoned = { _, _ -> events += "abandoned" },
                markStopping = { id -> events += "stopping:$id" },
                enqueueFinalize = { _, id -> events += "finalize:$id" },
                onCaptureStopHandoff = { id -> events += "handoff:$id" },
                onStaleHandoff = { events += "stale" },
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
            val stopGeneration = AtomicInteger(1)
            val events = mutableListOf<String>()

            RecordingStopHandoff.handoff(
                sessionId = sessionId,
                generation = 1,
                stopGeneration = stopGeneration,
                stopCapture = { events += "capture-stop-complete" },
                captureSnapshot = { snapshot(recordingId = null) },
                markAbandoned = { id, recordingId -> events += "abandoned:$id:$recordingId" },
                markStopping = { events += "stopping:$it" },
                enqueueFinalize = { _, _ -> events += "finalize" },
                onCaptureStopHandoff = { id -> events += "handoff:$id" },
                onStaleHandoff = { events += "stale" },
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

    @Test
    fun `generation bumped during capture stop skips all side effects`() =
        runTest {
            val sessionId = UUID.randomUUID()
            val stopGeneration = AtomicInteger(1)
            val events = mutableListOf<String>()

            val result =
                RecordingStopHandoff.handoff(
                    sessionId = sessionId,
                    generation = 1,
                    stopGeneration = stopGeneration,
                    stopCapture = {
                        events += "capture-stop-complete"
                        // A cancel or restart supersedes this stop while the capture winds down.
                        stopGeneration.incrementAndGet()
                    },
                    captureSnapshot = { events += "snapshot"; snapshot(UUID.randomUUID()) },
                    markAbandoned = { _, _ -> events += "abandoned" },
                    markStopping = { events += "stopping:$it" },
                    enqueueFinalize = { _, _ -> events += "finalize" },
                    onCaptureStopHandoff = { events += "handoff:$it" },
                    onStaleHandoff = { events += "stale" },
                )

            assertNull(result)
            assertEquals(listOf("capture-stop-complete", "stale"), events)
        }

    @Test
    fun `generation bumped during stopping marker skips finalize and completion`() =
        runTest {
            val recordingId = UUID.randomUUID()
            val sessionId = UUID.randomUUID()
            val stopGeneration = AtomicInteger(1)
            val events = mutableListOf<String>()

            val result =
                RecordingStopHandoff.handoff(
                    sessionId = sessionId,
                    generation = 1,
                    stopGeneration = stopGeneration,
                    stopCapture = { events += "capture-stop-complete" },
                    captureSnapshot = { snapshot(recordingId) },
                    markAbandoned = { _, _ -> events += "abandoned" },
                    markStopping = { id ->
                        events += "stopping:$id"
                        stopGeneration.incrementAndGet()
                    },
                    enqueueFinalize = { _, _ -> events += "finalize" },
                    onCaptureStopHandoff = { events += "handoff:$it" },
                    onStaleHandoff = { events += "stale" },
                )

            assertNull(result)
            assertEquals(
                listOf(
                    "capture-stop-complete",
                    "stopping:$sessionId",
                    "stale",
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
