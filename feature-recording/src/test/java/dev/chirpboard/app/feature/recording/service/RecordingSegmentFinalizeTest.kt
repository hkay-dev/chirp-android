package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.feature.recording.session.RecordingCapturePaths
import dev.chirpboard.app.feature.recording.session.RecordingSessionEntry
import dev.chirpboard.app.feature.recording.session.RecordingSessionJournal
import dev.chirpboard.app.feature.recording.session.SessionJournalState
import dev.chirpboard.app.feature.recording.session.validation.RecordingFileValidator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.UUID

class RecordingSegmentFinalizeTest {
    @Test
    fun `materializeExportFile reuses playable existing export before reading missing segments`() {
        val sessionId = UUID.randomUUID()
        val exportFile = File.createTempFile("recording-export", ".m4a")
        exportFile.writeBytes(playableM4aBytes())
        val missingActiveSegment = File(exportFile.parentFile, "missing-active.m4a")
        val sessionJournal = mockk<RecordingSessionJournal>()
        val segmentConcatenator = mockk<RecordingSegmentConcatenator>(relaxed = true)
        val capturePaths = mockk<RecordingCapturePaths>(relaxed = true)
        val classUnderTest =
            RecordingSegmentFinalize(
                sessionJournal = sessionJournal,
                segmentConcatenator = segmentConcatenator,
                capturePaths = capturePaths,
                fileValidator = RecordingFileValidator(),
            )

        every { sessionJournal.findBySessionId(sessionId) } returns
            RecordingSessionEntry(
                sessionId = sessionId,
                audioPath = missingActiveSegment.absolutePath,
                finalAudioPath = exportFile.absolutePath,
                segmentPaths = listOf(File(exportFile.parentFile, "missing-seg-000.m4a").absolutePath),
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = UUID.randomUUID(),
                startedAtEpochMs = 0L,
                lastHeartbeatEpochMs = 0L,
                lastSegmentFinalizedAtEpochMs = null,
                activeSegmentStartedAtEpochMs = 0L,
                fileBytes = exportFile.length(),
                checkpointPath = null,
                state = SessionJournalState.STOPPING,
                correlationId = "corr",
            )

        val result = classUnderTest.materializeExportFile(sessionId, missingActiveSegment.absolutePath)

        assertEquals(exportFile, result)
        verify(exactly = 0) { segmentConcatenator.concatToExport(any(), any()) }
        verify(exactly = 0) { capturePaths.deleteCaptureArtifacts(any()) }
        exportFile.delete()
    }

    private fun playableM4aBytes(): ByteArray =
        byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) +
            ByteArray(512) +
            "moov".encodeToByteArray()
}
