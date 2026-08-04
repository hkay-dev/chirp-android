package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.audio.WavFileWriter
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
                exportDurability = RecordingExportDurability(),
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

        assertEquals(exportFile, result?.file)
        // The materialization pass validated the export itself, so the stop path may skip
        // its duplicate full-file validation read.
        assertTrue(result?.validatedPlayable == true)
        verify(exactly = 0) { segmentConcatenator.concatToExport(any(), any()) }
        verify(exactly = 0) { capturePaths.deleteCaptureArtifacts(any()) }
        exportFile.delete()
    }

    @Test
    fun `materializeExportFile keeps capture artifacts when concatenated export is not playable`() {
        val sessionId = UUID.randomUUID()
        val workDir = createTempDir("finalize-keep-artifacts")
        val activeSegment = File(workDir, "seg-000.m4a").apply { writeBytes(playableM4aBytes()) }
        val exportFile = File(workDir, "recording.m4a")
        val sessionJournal = mockk<RecordingSessionJournal>()
        val segmentConcatenator = mockk<RecordingSegmentConcatenator>()
        val capturePaths = mockk<RecordingCapturePaths>(relaxed = true)
        val classUnderTest =
            RecordingSegmentFinalize(
                sessionJournal = sessionJournal,
                segmentConcatenator = segmentConcatenator,
                capturePaths = capturePaths,
                fileValidator = RecordingFileValidator(),
                exportDurability = RecordingExportDurability(),
            )
        every { sessionJournal.findBySessionId(sessionId) } returns
            segmentEntry(sessionId, activeSegment, exportFile)
        every { segmentConcatenator.concatToExport(any(), any()) } answers {
            // Concat reports success but produced an unplayable export file.
            exportFile.writeBytes(ByteArray(600))
            SegmentConcatResult.Success
        }

        val result = classUnderTest.materializeExportFile(sessionId, activeSegment.absolutePath)

        assertNull(result)
        // The segments are the only remaining audio source; they must survive.
        verify(exactly = 0) { capturePaths.deleteCaptureArtifacts(any()) }
    }

    @Test
    fun `materializeExportFile deletes capture artifacts only after playable export verdict`() {
        val sessionId = UUID.randomUUID()
        val workDir = createTempDir("finalize-delete-after")
        val activeSegment = File(workDir, "seg-000.m4a").apply { writeBytes(playableM4aBytes()) }
        val exportFile = File(workDir, "recording.m4a")
        val sessionJournal = mockk<RecordingSessionJournal>()
        val segmentConcatenator = mockk<RecordingSegmentConcatenator>()
        val capturePaths = mockk<RecordingCapturePaths>(relaxed = true)
        val classUnderTest =
            RecordingSegmentFinalize(
                sessionJournal = sessionJournal,
                segmentConcatenator = segmentConcatenator,
                capturePaths = capturePaths,
                fileValidator = RecordingFileValidator(),
                exportDurability = RecordingExportDurability(),
            )
        every { sessionJournal.findBySessionId(sessionId) } returns
            segmentEntry(sessionId, activeSegment, exportFile)
        every { segmentConcatenator.concatToExport(any(), any()) } answers {
            exportFile.writeBytes(playableM4aBytes())
            SegmentConcatResult.Success
        }

        val result = classUnderTest.materializeExportFile(sessionId, activeSegment.absolutePath)

        assertEquals(exportFile, result?.file)
        assertTrue(result?.validatedPlayable == true)
        verify(exactly = 1) { capturePaths.deleteCaptureArtifacts(sessionId) }
    }

    @Test
    fun `materializeExportFile keeps capture artifacts when export sync fails`() {
        val sessionId = UUID.randomUUID()
        val workDir = createTempDir("finalize-sync-failure")
        val activeSegment = File(workDir, "seg-000.m4a").apply { writeBytes(playableM4aBytes()) }
        val exportFile = File(workDir, "recording.m4a")
        val sessionJournal = mockk<RecordingSessionJournal>()
        val segmentConcatenator = mockk<RecordingSegmentConcatenator>()
        val capturePaths = mockk<RecordingCapturePaths>(relaxed = true)
        val exportDurability = mockk<RecordingExportDurability>()
        val classUnderTest =
            RecordingSegmentFinalize(
                sessionJournal = sessionJournal,
                segmentConcatenator = segmentConcatenator,
                capturePaths = capturePaths,
                fileValidator = RecordingFileValidator(),
                exportDurability = exportDurability,
            )
        every { sessionJournal.findBySessionId(sessionId) } returns
            segmentEntry(sessionId, activeSegment, exportFile)
        every { segmentConcatenator.concatToExport(any(), any()) } answers {
            exportFile.writeBytes(playableM4aBytes())
            SegmentConcatResult.Success
        }
        every { exportDurability.sync(exportFile) } returns false

        val result = classUnderTest.materializeExportFile(sessionId, activeSegment.absolutePath)

        assertNull(result)
        verify(exactly = 0) { capturePaths.deleteCaptureArtifacts(any()) }
    }

    @Test
    fun `materializeExportFile repairs stale-header WAV export when no segments remain`() {
        val sessionId = UUID.randomUUID()
        val workDir = createTempDir("finalize-legacy-export")
        val exportFile = File(workDir, "recording.wav")
        WavFileWriter(exportFile, sampleRate = 16_000).use { writer ->
            writer.appendPcm16(ByteArray(2048) { 1 }, 2048)
        }
        // Regress the data size to the crash-time placeholder a pre-fix version left.
        java.io.RandomAccessFile(exportFile, "rw").use { raf ->
            raf.seek(40)
            raf.write(byteArrayOf(0, 0, 0, 0))
        }
        val missingSegment = File(workDir, "missing-seg-000.wav")
        val sessionJournal = mockk<RecordingSessionJournal>()
        val segmentConcatenator = mockk<RecordingSegmentConcatenator>(relaxed = true)
        val capturePaths = mockk<RecordingCapturePaths>(relaxed = true)
        val classUnderTest =
            RecordingSegmentFinalize(
                sessionJournal = sessionJournal,
                segmentConcatenator = segmentConcatenator,
                capturePaths = capturePaths,
                fileValidator = RecordingFileValidator(),
                exportDurability = RecordingExportDurability(),
            )
        every { sessionJournal.findBySessionId(sessionId) } returns
            segmentEntry(sessionId, missingSegment, exportFile).copy(
                segmentPaths = listOf(missingSegment.absolutePath),
            )

        val result = classUnderTest.materializeExportFile(sessionId, missingSegment.absolutePath)

        assertEquals(exportFile, result?.file)
        assertTrue(WavFileWriter.hasAccurateHeader(exportFile))
        verify(exactly = 0) { segmentConcatenator.concatToExport(any(), any()) }
    }

    private fun segmentEntry(
        sessionId: UUID,
        activeSegment: File,
        exportFile: File,
    ): RecordingSessionEntry =
        RecordingSessionEntry(
            sessionId = sessionId,
            audioPath = activeSegment.absolutePath,
            finalAudioPath = exportFile.absolutePath,
            segmentPaths = listOf(activeSegment.absolutePath),
            origin = RecordingOrigin.APP,
            profileId = null,
            recordingId = UUID.randomUUID(),
            startedAtEpochMs = 0L,
            lastHeartbeatEpochMs = 0L,
            lastSegmentFinalizedAtEpochMs = null,
            activeSegmentStartedAtEpochMs = 0L,
            fileBytes = activeSegment.length(),
            checkpointPath = null,
            state = SessionJournalState.STOPPING,
            correlationId = "corr",
        )

    private fun playableM4aBytes(): ByteArray =
        byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) +
            ByteArray(512) +
            "moov".encodeToByteArray()
}
