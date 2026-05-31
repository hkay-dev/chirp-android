package dev.chirpboard.app.feature.transcription

import android.content.Context
import androidx.work.WorkManager
import dev.chirpboard.app.core.modelreadiness.ModelReadinessState
import dev.chirpboard.app.core.modelreadiness.ModelReadinessVerificationSource
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.entity.Recording
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.just
import io.mockk.runs
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.UUID

class TranscriptionQueueManagerTest {

    private lateinit var context: Context
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var constraintChecker: WorkConstraintChecker
    private lateinit var manager: TranscriptionQueueManager
    private lateinit var workManager: WorkManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        recordingRepository = mockk(relaxed = true)
        constraintChecker = mockk(relaxed = true)
        workManager = mockk(relaxed = true)

        io.mockk.mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager

        mockkObject(TranscriptionWorkRequest)
        every { TranscriptionWorkRequest.enqueue(any(), any(), any()) } returns "test-work-id"
        every { TranscriptionWorkRequest.workName(any()) } answers { "transcribe_${arg<UUID>(0)}" }
        mockkObject(RecordingEnhancementWorkRequest)
        every { RecordingEnhancementWorkRequest.enqueue(any(), any(), any()) } returns "test-enhancement-work-id"
        every { RecordingEnhancementWorkRequest.workName(any()) } answers { "enhance_${arg<UUID>(0)}" }

        mockkObject(ReliabilityEventLogger)
        every { ReliabilityEventLogger.newCorrelationId(any()) } returns "test-corr-id"
        every { ReliabilityEventLogger.log(any(), any(), any(), any(), any(), any()) } just runs

        coEvery { constraintChecker.checkConstraints() } returns WorkConstraintChecker.ConstraintStatus.Ready
        coEvery { constraintChecker.getConstraintMessage(any()) } returns null

        val readinessGate = mockk<SpeechModelReadinessGate>(relaxed = true)
        every { readinessGate.state } returns kotlinx.coroutines.flow.MutableStateFlow(ModelReadinessState.Ready(0L, ModelReadinessVerificationSource.PROCESS_CACHE))
        manager = TranscriptionQueueManager(context, recordingRepository, constraintChecker, mockk(relaxed = true), readinessGate)
    }

    @After
    fun tearDown() {
        io.mockk.unmockkStatic(WorkManager::class)
        unmockkObject(TranscriptionWorkRequest)
        unmockkObject(RecordingEnhancementWorkRequest)
        unmockkObject(ReliabilityEventLogger)
    }

    @Test
    fun `enqueue sets status to pending and schedules work`() = runTest {
        val id = UUID.randomUUID()
        
        manager.enqueue(id)

        coVerify { 
            recordingRepository.updateStatusWithError(id, RecordingStatus.PENDING_TRANSCRIPTION, null)
        }
        coVerify {
            TranscriptionWorkRequest.enqueue(context, id, any())
        }
    }

    @Test
    fun `retry resets status and re-enqueues if failed`() = runTest {
        val id = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.status } returns RecordingStatus.FAILED
        coEvery { recordingRepository.getRecording(id) } returns recording

        manager.retry(id)

        coVerify { 
            recordingRepository.updateStatusWithError(id, RecordingStatus.PENDING_TRANSCRIPTION, null)
        }
        coVerify {
            TranscriptionWorkRequest.enqueue(context, id, any())
        }
    }

    @Test
    fun `cancel cancels work manager job and sets status to failed if transcribing`() = runTest {
        val id = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.status } returns RecordingStatus.TRANSCRIBING
        coEvery { recordingRepository.getRecording(id) } returns recording

        manager.cancel(id)

        coVerify { workManager.cancelUniqueWork("transcribe_$id") }
        coVerify { workManager.cancelUniqueWork("enhance_$id") }
        coVerify { recordingRepository.updateStatusWithError(id, RecordingStatus.FAILED, "Cancelled by user") }
    }

    @Test
    fun `recover pending enhancement enqueues enhancement work`() = runTest {
        val id = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.status } returns RecordingStatus.PENDING_ENHANCEMENT
        coEvery { recordingRepository.getRecording(id) } returns recording
        every { workManager.getWorkInfosByTag("recording_$id") } returns com.google.common.util.concurrent.Futures.immediateFuture(emptyList())

        manager.recoverPendingEnhancement(id)

        coVerify {
            recordingRepository.updateStatusWithError(id, RecordingStatus.PENDING_ENHANCEMENT, any())
        }
        coVerify {
            RecordingEnhancementWorkRequest.enqueue(context, id, any())
        }
    }
}
