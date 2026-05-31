package dev.chirpboard.app.feature.transcription

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.Futures
import dev.chirpboard.app.core.modelreadiness.ModelReadinessState
import dev.chirpboard.app.core.modelreadiness.ModelReadinessVerificationSource
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.RepositoryFlowState
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
import org.junit.Rule
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import org.junit.Test
import java.util.Date
import java.util.UUID

class TranscriptionQueueOrchestrationTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()


    private lateinit var context: Context
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var constraintChecker: WorkConstraintChecker
    private lateinit var manager: TranscriptionQueueManager
    private lateinit var workManager: WorkManager
    private lateinit var reconciler: TranscriptionQueueReconciler

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
        every { workManager.getWorkInfosByTag(TranscriptionWorkRequest.WORK_TAG_TRANSCRIPTION) } returns Futures.immediateFuture(emptyList())
        every { workManager.getWorkInfosByTag(RecordingEnhancementWorkRequest.WORK_TAG_ENHANCEMENT) } returns Futures.immediateFuture(emptyList())

        val readinessGate = mockk<SpeechModelReadinessGate>(relaxed = true)
        every { readinessGate.state } returns kotlinx.coroutines.flow.MutableStateFlow(ModelReadinessState.Ready(0L, ModelReadinessVerificationSource.PROCESS_CACHE))
        manager = TranscriptionQueueManager(context, recordingRepository, constraintChecker, mockk(relaxed = true), readinessGate)
        reconciler = TranscriptionQueueReconciler(
            context, recordingRepository, constraintChecker, {}, {}
        )
    }

    @After
    fun tearDown() {
        io.mockk.unmockkStatic(WorkManager::class)
        unmockkObject(TranscriptionWorkRequest)
        unmockkObject(RecordingEnhancementWorkRequest)
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

        val workInfo = mockk<WorkInfo>()
        every { workInfo.state } returns WorkInfo.State.FAILED
        every { workManager.getWorkInfosForUniqueWork(any()) } returns Futures.immediateFuture(listOf(workInfo))

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

        val workInfo = mockk<WorkInfo>()
        every { workInfo.state } returns WorkInfo.State.CANCELLED
        every { workManager.getWorkInfosForUniqueWork(any()) } returns Futures.immediateFuture(listOf(workInfo))

        reconciler.reconcileQueueHealth(ReconciliationTrigger.PERIODIC)

        coVerify {
            TranscriptionWorkRequest.enqueue(context, id, any())
        }
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

        val workInfo = mockk<WorkInfo>()
        every { workInfo.state } returns WorkInfo.State.CANCELLED
        every { workManager.getWorkInfosForUniqueWork("enhance_$id") } returns Futures.immediateFuture(listOf(workInfo))

        reconciler.reconcileQueueHealth(ReconciliationTrigger.PERIODIC)

        coVerify {
            RecordingEnhancementWorkRequest.enqueue(context, id, any())
        }
    }
}
