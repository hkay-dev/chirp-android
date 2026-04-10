package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.feature.transcription.TranscriptionQueueManager
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
    private lateinit var transcriptionQueueManager: TranscriptionQueueManager
    private lateinit var orchestrator: RecordingStopOrchestrator

    @Before
    fun setup() {
        recordingRepository = mockk(relaxed = true)
        transcriptionQueueManager = mockk(relaxed = true)

        mockkObject(ReliabilityEventLogger)
        every { ReliabilityEventLogger.log(any(), any(), any(), any(), any(), any()) } just runs

        orchestrator = RecordingStopOrchestrator(recordingRepository, transcriptionQueueManager)
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
        file.writeText("audio data")
        
        val recordingId = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.id } returns recordingId
        
        coEvery { 
            recordingRepository.createRecording(any(), file.absolutePath, any(), any(), any()) 
        } returns recording

        val snapshot = StopSnapshot(
            origin = RecordingOrigin.APP,
            profileId = null,
            audioFilePath = file.absolutePath,
            durationMs = 1000L,
            stoppedAtEpochMs = System.currentTimeMillis(),
            wasPaused = false,
            correlationId = "corr-id"
        )

        val result = orchestrator.persistAndQueueRecording(snapshot)

        assertTrue(result is StopPersistenceResult.SavedAndQueued)
        assertEquals(recordingId, (result as StopPersistenceResult.SavedAndQueued).recordingId)

        coVerify { transcriptionQueueManager.enqueue(recordingId, "corr-id") }
        
        file.delete()
    }

    @Test
    fun `persistAndQueueRecording returns SavedPendingRecovery if enqueue fails`() = runTest {
        val file = File.createTempFile("test_audio", ".m4a")
        file.writeText("audio data")
        
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
