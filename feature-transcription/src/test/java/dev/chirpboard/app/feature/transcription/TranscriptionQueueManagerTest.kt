package dev.chirpboard.app.feature.transcription

import android.content.Context
import androidx.work.WorkManager
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

        mockkObject(TranscriptionWorkRequest.Companion)
        every { TranscriptionWorkRequest.enqueue(any(), any(), any()) } returns "test-work-id"
        every { TranscriptionWorkRequest.workName(any()) } answers { "transcribe_${arg<UUID>(0)}" }

        mockkObject(ReliabilityEventLogger.Companion)
        every { ReliabilityEventLogger.newCorrelationId(any()) } returns "test-corr-id"
        every { ReliabilityEventLogger.log(any(), any(), any(), any(), any(), any()) } just runs

        coEvery { constraintChecker.checkConstraints() } returns WorkConstraintStatus.SATISFIED
        coEvery { constraintChecker.getConstraintMessage(any()) } returns null

        manager = TranscriptionQueueManager(context, recordingRepository, constraintChecker)
    }

    @After
    fun tearDown() {
        io.mockk.unmockkStatic(WorkManager::class)
        unmockkObject(TranscriptionWorkRequest.Companion)
        unmockkObject(ReliabilityEventLogger.Companion)
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
    fun `cancel cancels work manager job and sets status to pending if transcribing`() = runTest {
        val id = UUID.randomUUID()
        val recording = mockk<Recording>()
        every { recording.status } returns RecordingStatus.TRANSCRIBING
        coEvery { recordingRepository.getRecording(id) } returns recording

        manager.cancel(id)

        coVerify { workManager.cancelUniqueWork("transcribe_$id") }
        coVerify { recordingRepository.updateStatus(id, RecordingStatus.PENDING_TRANSCRIPTION) }
    }
}
