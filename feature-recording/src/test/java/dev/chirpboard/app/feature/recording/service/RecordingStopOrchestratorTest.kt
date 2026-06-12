package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.audio.recorder.AudioEncoder
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.feature.recording.session.validation.RecordingFileValidator
import dev.chirpboard.app.feature.recording.util.RecordingTitleFormatter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.just
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import io.mockk.unmockkObject
import io.mockk.mockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingStopOrchestratorTest {

    private lateinit var recordingRepository: RecordingRepository
    private lateinit var transcriptionQueueManager: TranscriptionRecovery
    private lateinit var orchestrator: RecordingStopOrchestrator

    @Before
    fun setup() {
        recordingRepository = mockk(relaxed = true)
        transcriptionQueueManager = mockk(relaxed = true)

        mockkObject(ReliabilityEventLogger)
        every { ReliabilityEventLogger.log(any(), any(), any(), any(), any(), any()) } just runs

        orchestrator =
            RecordingStopOrchestrator(
                recordingRepository,
                transcriptionQueueManager,
                RecordingFileValidator(),
                mockk(relaxed = true),
                RecordingSegmentFinalize(
                    mockk(relaxed = true),
                    RecordingSegmentConcatenator(mockk<AudioEncoder>(relaxed = true)),
                    mockk(relaxed = true),
                    RecordingFileValidator(),
                ),
                mockk<RecordingTitleFormatter> {
                    every { format(any()) } returns "Jun 12, 3:42 PM"
                },
            )
    }

    @After
    fun tearDown() {
        unmockkObject(ReliabilityEventLogger)
    }

    @Test
    fun `persistAndQueueRecording returns NoAudioFile when path is null`() = runTest {
        val snapshot = StopSnapshot(
            origin = RecordingOrigin.APP,
            profileId = null,
            recordingId = null,
            audioFilePath = null,
            durationMs = 1000L,
            stoppedAtEpochMs = 0L,
            wasPaused = false,
            correlationId = "corr-id"
        )
        val result = orchestrator.persistAndQueueRecording(snapshot)
        assertTrue(result is StopPersistenceResult.NoAudioFile)
    }

    @Test
    fun `persistAndQueueRecording saves and enqueues on success`() = runTest {
        val file = File.createTempFile("test_audio", ".m4a")
        file.writeBytes(
            byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) +
                ByteArray(512) + "moov".encodeToByteArray(),
        )
        
        val recordingId = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.id } returns recordingId
        
        coEvery { 
            recordingRepository.createRecording(any(), file.absolutePath, any(), any(), any()) 
        } returns recording

        val snapshot = StopSnapshot(
            origin = RecordingOrigin.APP,
            profileId = null,
            recordingId = null,
            audioFilePath = file.absolutePath,
            durationMs = 1000L,
            stoppedAtEpochMs = System.currentTimeMillis(),
            wasPaused = false,
            correlationId = "corr-id"
        )

        val result = orchestrator.persistAndQueueRecording(snapshot)

        assertTrue(result is StopPersistenceResult.SavedAndQueued)
        assertEquals(recordingId, (result as StopPersistenceResult.SavedAndQueued).recordingId)

        coVerify(exactly = 1) {
            recordingRepository.createRecording(any(), file.absolutePath, any(), any(), any())
        }
        coVerify { transcriptionQueueManager.enqueue(recordingId, "corr-id") }
        
        file.delete()
    }

    @Test
    fun `persistAndQueueRecording finalizes linked in-progress row with export audio path`() = runTest {
        val file = File.createTempFile("test_audio", ".m4a")
        file.writeBytes(
            byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) +
                ByteArray(512) + "moov".encodeToByteArray(),
        )

        val recordingId = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.id } returns recordingId

        coEvery {
            recordingRepository.finalizeInProgressRecording(recordingId, any(), any(), file.absolutePath)
        } returns recording

        val snapshot = StopSnapshot(
            origin = RecordingOrigin.APP,
            profileId = null,
            recordingId = recordingId,
            audioFilePath = file.absolutePath,
            durationMs = 1000L,
            stoppedAtEpochMs = System.currentTimeMillis(),
            wasPaused = false,
            correlationId = "corr-id"
        )

        val result = orchestrator.persistAndQueueRecording(snapshot)

        assertTrue(result is StopPersistenceResult.SavedAndQueued)
        assertEquals(recordingId, (result as StopPersistenceResult.SavedAndQueued).recordingId)

        // The export path must be stamped onto the row: a finalize that keeps the
        // original (possibly deleted) segment path would orphan the row's audio.
        coVerify(exactly = 1) {
            recordingRepository.finalizeInProgressRecording(recordingId, any(), any(), file.absolutePath)
        }
        coVerify(exactly = 0) { recordingRepository.createRecording(any(), any(), any(), any(), any()) }
        coVerify { transcriptionQueueManager.enqueue(recordingId, "corr-id") }

        file.delete()
    }

    @Test
    fun `persistAndQueueRecording creates replacement row when linked row cannot be finalized`() = runTest {
        val file = File.createTempFile("test_audio", ".m4a")
        file.writeBytes(
            byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) +
                ByteArray(512) + "moov".encodeToByteArray(),
        )

        val recordingId = UUID.randomUUID()
        val replacementId = UUID.randomUUID()
        val replacement = mockk<Recording>()
        every { replacement.id } returns replacementId

        coEvery {
            recordingRepository.finalizeInProgressRecording(recordingId, any(), any(), file.absolutePath)
        } returns null
        coEvery {
            recordingRepository.createRecording(any(), file.absolutePath, any(), any(), any())
        } returns replacement

        val snapshot = StopSnapshot(
            origin = RecordingOrigin.APP,
            profileId = null,
            recordingId = recordingId,
            audioFilePath = file.absolutePath,
            durationMs = 1000L,
            stoppedAtEpochMs = System.currentTimeMillis(),
            wasPaused = false,
            correlationId = "corr-id"
        )

        val result = orchestrator.persistAndQueueRecording(snapshot)

        assertTrue(result is StopPersistenceResult.SavedAndQueued)
        assertEquals(replacementId, (result as StopPersistenceResult.SavedAndQueued).recordingId)
        coVerify(exactly = 1) {
            recordingRepository.createRecording(any(), file.absolutePath, any(), any(), any())
        }

        file.delete()
    }

    @Test
    fun `finalized recording is stamped with the probed container duration`() = runTest {
        // Regression (sweep-03/04): the finalize path must stamp a real duration at save
        // time; relying on a later backfill leaves 0:00 in every duration surface.
        val file = File.createTempFile("test_audio", ".m4a")
        file.writeBytes(
            byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) +
                ByteArray(512) + "moov".encodeToByteArray(),
        )

        val recordingId = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.id } returns recordingId
        coEvery {
            recordingRepository.finalizeInProgressRecording(recordingId, any(), any(), file.absolutePath)
        } returns recording

        mockkStatic("dev.chirpboard.app.feature.recording.util.AudioDurationProbeKt")
        try {
            every { dev.chirpboard.app.feature.recording.util.probeDurationMs(any()) } returns 38_000L

            val snapshot = StopSnapshot(
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = recordingId,
                audioFilePath = file.absolutePath,
                durationMs = 0L,
                stoppedAtEpochMs = System.currentTimeMillis(),
                wasPaused = false,
                correlationId = "corr-id"
            )

            val result = orchestrator.persistAndQueueRecording(snapshot)

            assertTrue(result is StopPersistenceResult.SavedAndQueued)
            coVerify(exactly = 1) {
                recordingRepository.finalizeInProgressRecording(recordingId, 38_000L, any(), file.absolutePath)
            }
        } finally {
            unmockkStatic("dev.chirpboard.app.feature.recording.util.AudioDurationProbeKt")
            file.delete()
        }
    }

    @Test
    fun `finalized recording falls back to the snapshot duration when the container probe fails`() = runTest {
        val file = File.createTempFile("test_audio", ".m4a")
        file.writeBytes(
            byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) +
                ByteArray(512) + "moov".encodeToByteArray(),
        )

        val recordingId = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.id } returns recordingId
        coEvery {
            recordingRepository.finalizeInProgressRecording(recordingId, any(), any(), file.absolutePath)
        } returns recording

        mockkStatic("dev.chirpboard.app.feature.recording.util.AudioDurationProbeKt")
        try {
            every { dev.chirpboard.app.feature.recording.util.probeDurationMs(any()) } returns 0L

            val snapshot = StopSnapshot(
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = recordingId,
                audioFilePath = file.absolutePath,
                durationMs = 37_500L,
                stoppedAtEpochMs = System.currentTimeMillis(),
                wasPaused = false,
                correlationId = "corr-id"
            )

            val result = orchestrator.persistAndQueueRecording(snapshot)

            assertTrue(result is StopPersistenceResult.SavedAndQueued)
            // A finalized recording must never persist durationMs = 0 while a measured
            // duration is available from the stop snapshot.
            coVerify(exactly = 1) {
                recordingRepository.finalizeInProgressRecording(recordingId, 37_500L, any(), file.absolutePath)
            }
        } finally {
            unmockkStatic("dev.chirpboard.app.feature.recording.util.AudioDurationProbeKt")
            file.delete()
        }
    }

    @Test
    fun `persistAndQueueRecording returns SavedPendingRecovery if enqueue fails`() = runTest {
        val file = File.createTempFile("test_audio", ".m4a")
        file.writeBytes(
            byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()) +
                ByteArray(512) + "moov".encodeToByteArray(),
        )
        
        val recordingId = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.id } returns recordingId
        
        coEvery { 
            recordingRepository.createRecording(any(), file.absolutePath, any(), any(), any()) 
        } returns recording
        
        val exception = RuntimeException("Enqueue failed")
        coEvery { transcriptionQueueManager.enqueue(recordingId, "corr-id") } throws exception

        val snapshot = StopSnapshot(
            origin = RecordingOrigin.APP,
            profileId = null,
            recordingId = null,
            audioFilePath = file.absolutePath,
            durationMs = 1000L,
            stoppedAtEpochMs = System.currentTimeMillis(),
            wasPaused = false,
            correlationId = "corr-id"
        )

        val result = orchestrator.persistAndQueueRecording(snapshot)

        assertTrue(result is StopPersistenceResult.SavedPendingRecovery)
        assertEquals(recordingId, (result as StopPersistenceResult.SavedPendingRecovery).recordingId)
        assertEquals(exception, result.cause)

        coVerify { transcriptionQueueManager.markPendingForQueueRecovery(recordingId, any(), exception) }
        
        file.delete()
    }
}
