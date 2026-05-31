package dev.chirpboard.app.feature.transcription

import dev.chirpboard.app.core.modelreadiness.ModelReadinessState
import dev.chirpboard.app.core.modelreadiness.ModelReadinessVerificationSource
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.RepositoryFlowState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class TranscriptionQueueManagerTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    private lateinit var recordingRepository: RecordingRepository
    private lateinit var constraintChecker: WorkConstraintChecker
    private lateinit var manager: TranscriptionQueueManager
    private lateinit var workScheduler: FakeTranscriptionWorkScheduler

    @Before
    fun setup() {
        recordingRepository = mockk(relaxed = true)
        constraintChecker = mockk(relaxed = true)
        workScheduler = FakeTranscriptionWorkScheduler()

        mockkObject(ReliabilityEventLogger)
        every { ReliabilityEventLogger.newCorrelationId(any()) } returns "test-corr-id"
        every { ReliabilityEventLogger.log(any(), any(), any(), any(), any(), any()) } just runs

        coEvery { constraintChecker.checkConstraints() } returns WorkConstraintChecker.ConstraintStatus.Ready
        coEvery { constraintChecker.getConstraintMessage(any()) } returns null

        val readinessGate = mockk<SpeechModelReadinessGate>(relaxed = true)
        every { readinessGate.state } returns kotlinx.coroutines.flow.MutableStateFlow(ModelReadinessState.Ready(0L, ModelReadinessVerificationSource.PROCESS_CACHE))
        manager =
            TranscriptionQueueManager(
                recordingRepository = recordingRepository,
                constraintChecker = constraintChecker,
                transcriberProvider = mockk(relaxed = true),
                readinessGate = readinessGate,
                workScheduler = workScheduler,
            )
    }

    @After
    fun tearDown() {
        unmockkObject(ReliabilityEventLogger)
    }

    @Test
    fun `enqueue sets status to pending and schedules work`() = runTest {
        val id = UUID.randomUUID()
        
        manager.enqueue(id)

        coVerify {
            recordingRepository.claimTranscriptionExecution(id, any(), RecordingStatus.PENDING_TRANSCRIPTION, null)
        }
        assertEquals(listOf(TranscriptionWorkRequest.workName(id)), workScheduler.transcriptions.map { it.workName })
    }

    @Test
    fun `retry resets status and re-enqueues if failed`() = runTest {
        val id = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.status } returns RecordingStatus.FAILED
        coEvery { recordingRepository.getRecording(id) } returns recording
        coEvery { recordingRepository.hasUnresolvedEnhancementSnapshot(id) } returns false

        manager.retry(id)

        coVerify {
            recordingRepository.claimTranscriptionExecution(id, any(), RecordingStatus.PENDING_TRANSCRIPTION, null)
        }
        assertEquals(listOf(TranscriptionWorkRequest.workName(id)), workScheduler.transcriptions.map { it.workName })
    }

    @Test
    fun `retry enqueues enhancement when failed recording has unresolved enhancement snapshot`() = runTest {
        val id = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.status } returns RecordingStatus.FAILED
        coEvery { recordingRepository.getRecording(id) } returns recording
        coEvery { recordingRepository.hasUnresolvedEnhancementSnapshot(id) } returns true
        coEvery { recordingRepository.claimEnhancementExecution(id, any(), any(), any()) } returns true

        manager.retry(id)

        coVerify(exactly = 1) {
            recordingRepository.claimEnhancementExecution(id, any(), RecordingStatus.PENDING_ENHANCEMENT, null)
        }
        coVerify(exactly = 0) {
            recordingRepository.claimTranscriptionExecution(id, any(), any(), any())
        }
        assertEquals(emptyList<String>(), workScheduler.transcriptions.map { it.workName })
        assertEquals(listOf(RecordingEnhancementWorkRequest.workName(id)), workScheduler.enhancements.map { it.workName })
    }

    @Test
    fun `startup retries recordings failed by foreground service policy`() = runTest {
        val id = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.id } returns id
        every { recording.status } returns RecordingStatus.FAILED
        every { recording.errorMessage } returns
            "startForegroundService() not allowed due to mAllowStartForeground false: service dev.chirpboard.app/androidx.work.impl.foreground.SystemForegroundService"

        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.TRANSCRIBING) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_ENHANCEMENT) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.ENHANCING) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.FAILED) } returns flowOf(RepositoryFlowState(listOf(recording)))
        coEvery { recordingRepository.getRecording(id) } returns recording
        coEvery { recordingRepository.hasUnresolvedEnhancementSnapshot(id) } returns false

        manager.processPendingOnStartup()

        coVerify {
            recordingRepository.claimTranscriptionExecution(id, any(), RecordingStatus.PENDING_TRANSCRIPTION, null)
        }
        assertEquals(listOf(TranscriptionWorkRequest.workName(id)), workScheduler.transcriptions.map { it.workName })
    }

    @Test
    fun `cancel cancels work manager job and sets status to failed if transcribing`() = runTest {
        val id = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.status } returns RecordingStatus.TRANSCRIBING
        coEvery { recordingRepository.getRecording(id) } returns recording

        manager.cancel(id)

        assertEquals(listOf(id), workScheduler.cancelledTranscriptions)
        assertEquals(listOf(id), workScheduler.cancelledEnhancements)
        coVerify { recordingRepository.updateStatusWithError(id, RecordingStatus.FAILED, "Cancelled by user") }
    }

    @Test
    fun `recover pending enhancement enqueues enhancement work`() = runTest {
        val id = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.status } returns RecordingStatus.PENDING_ENHANCEMENT
        coEvery { recordingRepository.getRecording(id) } returns recording
        coEvery { recordingRepository.claimEnhancementExecution(id, any(), any(), any()) } returns true

        manager.recoverPendingEnhancement(id)

        coVerify {
            recordingRepository.claimEnhancementExecution(id, any(), RecordingStatus.PENDING_ENHANCEMENT, any())
        }
        assertEquals(listOf(RecordingEnhancementWorkRequest.workName(id)), workScheduler.enhancements.map { it.workName })
    }
}
