package dev.chirpboard.app.download

import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.modelreadiness.VerificationTrigger
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.RepositoryFlowState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.util.UUID

class SpeechModelWarmupCoordinatorTest {
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var readinessGate: SpeechModelReadinessGate
    private lateinit var coordinator: SpeechModelWarmupCoordinator

    @Before
    fun setup() {
        recordingRepository = mockk()
        readinessGate = mockk(relaxed = true)
        coordinator = SpeechModelWarmupCoordinator(recordingRepository, readinessGate)
    }

    @Test
    fun `idle startup skips speech model warmup`() = runTest {
        coEvery { recordingRepository.getPendingRecordings() } returns emptyList()
        every { recordingRepository.getRecordingsByStatus(RecordingStatus.FAILED) } returns
            flowOf(RepositoryFlowState(emptyList()))

        coordinator.warmupOnAppStartupIfCandidate()

        verify(exactly = 0) { readinessGate.warmupIfNeeded(any()) }
    }

    @Test
    fun `queued transcription startup warms for queued candidate`() = runTest {
        coEvery { recordingRepository.getPendingRecordings() } returns
            listOf(recording(status = RecordingStatus.PENDING_TRANSCRIPTION))

        coordinator.warmupOnAppStartupIfCandidate()

        verify { readinessGate.warmupIfNeeded(VerificationTrigger.QUEUED_TRANSCRIPTION) }
    }

    @Test
    fun `recovery startup warms for model recovery candidate`() = runTest {
        coEvery { recordingRepository.getPendingRecordings() } returns emptyList()
        every { recordingRepository.getRecordingsByStatus(RecordingStatus.FAILED) } returns
            flowOf(
                RepositoryFlowState(
                    listOf(
                        recording(
                            status = RecordingStatus.FAILED,
                            errorMessage = "Recognizer not ready after download",
                        ),
                    ),
                ),
            )

        coordinator.warmupOnAppStartupIfCandidate()

        verify { readinessGate.warmupIfNeeded(VerificationTrigger.RECOVERY) }
    }

    private fun recording(
        status: RecordingStatus,
        errorMessage: String? = null,
    ): Recording =
        Recording(
            id = UUID.randomUUID(),
            title = "Test",
            audioPath = "/tmp/test.m4a",
            source = RecordingSource.APP,
            status = status,
            errorMessage = errorMessage,
        )
}
