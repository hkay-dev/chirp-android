package dev.chirpboard.app.feature.transcription

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.Futures
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.entity.Recording
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.just
import io.mockk.runs
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Date
import java.util.UUID

class TranscriptionQueueOrchestrationTest {

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

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns workManager

        mockkStatic(TranscriptionWorkRequest::class)
        every { TranscriptionWorkRequest.enqueue(any(), any(), any()) } just runs
        every { TranscriptionWorkRequest.workName(any()) } answers { "transcribe_${arg<UUID>(1)}" }

        mockkStatic(ReliabilityEventLogger::class)
        every { ReliabilityEventLogger.newCorrelationId(any()) } returns "test-corr-id"
        every { ReliabilityEventLogger.log(any(), any(), any(), any(), any(), any()) } just runs

        coEvery { constraintChecker.checkConstraints() } returns WorkConstraintStatus.SATISFIED
        coEvery { constraintChecker.getConstraintMessage(any()) } returns null

        manager = TranscriptionQueueManager(context, recordingRepository, constraintChecker)
        reconciler = TranscriptionQueueReconciler(
            context, recordingRepository, constraintChecker, {}, {}
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(WorkManager::class)
        unmockkStatic(TranscriptionWorkRequest::class)
        unmockkStatic(ReliabilityEventLogger::class)
    }

    @Test
    fun `reconcileQueueHealth recovers stale transcribing recordings`() = runTest {
        val staleRecording = mockk<Recording>()
        val id = UUID.randomUUID()
        every { staleRecording.id } returns id
        // 20 minutes ago
        every { staleRecording.createdAt } returns Date(System.currentTimeMillis() - 20 * 60_000L)
        
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.TRANSCRIBING) } returns flowOf(listOf(staleRecording))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION) } returns flowOf(emptyList())
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_ENHANCEMENT) } returns flowOf(emptyList())
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.ENHANCING) } returns flowOf(emptyList())

        val workInfo = mockk<WorkInfo>()
        every { workInfo.state } returns WorkInfo.State.FAILED
        every { workManager.getWorkInfosForUniqueWork(any()) } returns Futures.immediateFuture(listOf(workInfo))

        reconciler.reconcileQueueHealth(ReconciliationTrigger.PERIODIC)

        coVerify {
            recordingRepository.updateStatusWithError(id, RecordingStatus.PENDING_TRANSCRIPTION, match { it.contains("Recovered stale transcribing") })
        }
    }

    @Test
    fun `reconcileQueueHealth requeues pending recordings without active work`() = runTest {
        val pendingRecording = mockk<Recording>()
        val id = UUID.randomUUID()
        every { pendingRecording.id } returns id
        every { pendingRecording.status } returns RecordingStatus.PENDING_TRANSCRIPTION
        every { pendingRecording.errorMessage } returns null
        
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.TRANSCRIBING) } returns flowOf(emptyList())
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION) } returns flowOf(listOf(pendingRecording))
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.PENDING_ENHANCEMENT) } returns flowOf(emptyList())
        coEvery { recordingRepository.getRecordingsByStatus(RecordingStatus.ENHANCING) } returns flowOf(emptyList())

        val workInfo = mockk<WorkInfo>()
        every { workInfo.state } returns WorkInfo.State.CANCELLED
        every { workManager.getWorkInfosForUniqueWork(any()) } returns Futures.immediateFuture(listOf(workInfo))

        reconciler.reconcileQueueHealth(ReconciliationTrigger.PERIODIC)

        coVerify {
            TranscriptionWorkRequest.enqueue(context, id, any())
        }
    }
}
