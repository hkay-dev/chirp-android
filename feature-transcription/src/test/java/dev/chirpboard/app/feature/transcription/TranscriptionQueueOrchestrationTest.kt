package dev.chirpboard.app.feature.transcription

import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.RecordingStatusTransitionResult
import dev.chirpboard.app.data.repository.RepositoryFlowState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Date
import java.util.UUID

class TranscriptionQueueOrchestrationTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()


    private lateinit var recordingRepository: RecordingRepository
    private lateinit var constraintChecker: WorkConstraintChecker
    private lateinit var reconciler: TranscriptionQueueReconciler
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
        coEvery { recordingRepository.claimTranscriptionExecution(any(), any(), any(), any()) } returns true

        reconciler =
            TranscriptionQueueReconciler(
                recordingRepository = recordingRepository,
                constraintChecker = constraintChecker,
                workScheduler = workScheduler,
                setConstraintWarning = {},
                setActiveCount = {},
            )
    }

    @After
    fun tearDown() {
        unmockkObject(ReliabilityEventLogger)
    }

    @Test
    fun `reconcileQueueHealth recovers stale transcribing recordings`() = runTest {
        val staleRecording = mockk<Recording>()
        val id = UUID.randomUUID()
        every { staleRecording.id } returns id
        every { staleRecording.status } returns RecordingStatus.TRANSCRIBING
        // 20 minutes ago
        every { staleRecording.createdAt } returns Date(System.currentTimeMillis() - 20 * 60_000L)
        
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.TRANSCRIBING) } returns flowOf(RepositoryFlowState(listOf(staleRecording)))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_ENHANCEMENT) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.ENHANCING) } returns flowOf(RepositoryFlowState(emptyList()))

        workScheduler.uniqueWorkInfos[TranscriptionWorkRequest.workName(id)] =
            listOf(ScheduledWorkInfo(ScheduledWorkState.FAILED))

        reconciler.reconcileQueueHealth(ReconciliationTrigger.PERIODIC)

        coVerify {
            recordingRepository.updateStatusWithError(id, RecordingStatus.PENDING_TRANSCRIPTION, match { it?.contains("Recovered stale transcribing") == true })
        }
    }

    @Test
    fun `reconcileQueueHealth requeues pending recordings without active work`() = runTest {
        val pendingRecording = mockk<Recording>()
        val id = UUID.randomUUID()
        every { pendingRecording.id } returns id
        every { pendingRecording.status } returns RecordingStatus.PENDING_TRANSCRIPTION
        every { pendingRecording.errorMessage } returns null
        
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.TRANSCRIBING) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION) } returns flowOf(RepositoryFlowState(listOf(pendingRecording)))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_ENHANCEMENT) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.ENHANCING) } returns flowOf(RepositoryFlowState(emptyList()))

        workScheduler.uniqueWorkInfos[TranscriptionWorkRequest.workName(id)] =
            listOf(ScheduledWorkInfo(ScheduledWorkState.CANCELLED))

        reconciler.reconcileQueueHealth(ReconciliationTrigger.PERIODIC)

        assertEquals(listOf(TranscriptionWorkRequest.workName(id)), workScheduler.transcriptions.map { it.workName })
    }

    @Test
    fun `reconcileQueueHealth does not requeue pending recording when claim is rejected`() = runTest {
        val pendingRecording = mockk<Recording>()
        val id = UUID.randomUUID()
        every { pendingRecording.id } returns id
        every { pendingRecording.status } returns RecordingStatus.PENDING_TRANSCRIPTION
        every { pendingRecording.errorMessage } returns null

        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.TRANSCRIBING) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION) } returns flowOf(RepositoryFlowState(listOf(pendingRecording)))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_ENHANCEMENT) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.ENHANCING) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.claimTranscriptionExecution(id, any(), any(), any()) } returns false

        workScheduler.uniqueWorkInfos[TranscriptionWorkRequest.workName(id)] =
            listOf(ScheduledWorkInfo(ScheduledWorkState.CANCELLED))

        reconciler.reconcileQueueHealth(ReconciliationTrigger.PERIODIC)

        assertEquals(emptyList<String>(), workScheduler.transcriptions.map { it.workName })
    }

    @Test
    fun `reconcileQueueHealth logs skipped and keeps recovery marker when requeue claim is rejected`() = runTest {
        val pendingRecording = mockk<Recording>()
        val id = UUID.randomUUID()
        every { pendingRecording.id } returns id
        every { pendingRecording.status } returns RecordingStatus.PENDING_TRANSCRIPTION
        every { pendingRecording.errorMessage } returns "${RECOVERABLE_QUEUE_HANDOFF_PREFIX}Enqueue failed"

        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.TRANSCRIBING) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION) } returns flowOf(RepositoryFlowState(listOf(pendingRecording)))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_ENHANCEMENT) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.ENHANCING) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.claimTranscriptionExecution(id, any(), any(), any()) } returns false

        workScheduler.uniqueWorkInfos[TranscriptionWorkRequest.workName(id)] =
            listOf(ScheduledWorkInfo(ScheduledWorkState.CANCELLED))

        reconciler.reconcileQueueHealth(ReconciliationTrigger.PERIODIC)

        verify(exactly = 1) {
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.QUEUE_ENQUEUE,
                outcome = ReliabilityOutcome.SKIPPED,
                correlationId = any(),
                recordingId = id,
                reasonCode = "reconcile_claim_rejected",
            )
        }
        verify(exactly = 0) {
            ReliabilityEventLogger.log(
                stage = any(),
                outcome = ReliabilityOutcome.RECOVERED,
                correlationId = any(),
                recordingId = id,
                reasonCode = "reconciled_pending",
            )
        }
        coVerify(exactly = 0) {
            recordingRepository.transitionRecordingStatus(any(), any(), any(), any())
        }
        coVerify(exactly = 0) {
            recordingRepository.updateStatusWithError(any(), any(), any())
        }
    }

    @Test
    fun `reconcileQueueHealth clears recovery marker pinned to the loaded pending status`() = runTest {
        val pendingRecording = mockk<Recording>()
        val id = UUID.randomUUID()
        every { pendingRecording.id } returns id
        every { pendingRecording.status } returns RecordingStatus.PENDING_TRANSCRIPTION
        every { pendingRecording.errorMessage } returns "${RECOVERABLE_QUEUE_HANDOFF_PREFIX}Enqueue failed"

        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.TRANSCRIBING) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION) } returns flowOf(RepositoryFlowState(listOf(pendingRecording)))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_ENHANCEMENT) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.ENHANCING) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery {
            recordingRepository.transitionRecordingStatus(any(), any(), any(), any())
        } returns RecordingStatusTransitionResult.TransitionApplied

        workScheduler.uniqueWorkInfos[TranscriptionWorkRequest.workName(id)] =
            listOf(ScheduledWorkInfo(ScheduledWorkState.RUNNING))

        reconciler.reconcileQueueHealth(ReconciliationTrigger.PERIODIC)

        coVerify(exactly = 1) {
            recordingRepository.transitionRecordingStatus(
                id = id,
                destinationStatus = RecordingStatus.PENDING_TRANSCRIPTION,
                allowedSourceStatuses = listOf(RecordingStatus.PENDING_TRANSCRIPTION),
                errorMessage = null,
            )
        }
        coVerify(exactly = 0) {
            recordingRepository.updateStatusWithError(any(), any(), any())
        }
        assertEquals(emptyList<String>(), workScheduler.transcriptions.map { it.workName })
    }

    @Test
    fun `reconcileQueueHealth requeues pending enhancement without active work`() = runTest {
        val pendingRecording = mockk<Recording>()
        val id = UUID.randomUUID()
        every { pendingRecording.id } returns id
        every { pendingRecording.status } returns RecordingStatus.PENDING_ENHANCEMENT
        every { pendingRecording.errorMessage } returns null

        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.TRANSCRIBING) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION) } returns flowOf(RepositoryFlowState(emptyList()))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_ENHANCEMENT) } returns flowOf(RepositoryFlowState(listOf(pendingRecording)))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.ENHANCING) } returns flowOf(RepositoryFlowState(emptyList()))

        coEvery { recordingRepository.claimEnhancementExecution(id, any(), any(), any()) } returns true
        workScheduler.uniqueWorkInfos[RecordingEnhancementWorkRequest.workName(id)] =
            listOf(ScheduledWorkInfo(ScheduledWorkState.CANCELLED))

        reconciler.reconcileQueueHealth(ReconciliationTrigger.PERIODIC)

        assertEquals(listOf(RecordingEnhancementWorkRequest.workName(id)), workScheduler.enhancements.map { it.workName })
    }
}
