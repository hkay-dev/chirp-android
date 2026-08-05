package dev.chirpboard.app.download

import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.modelreadiness.VerificationTrigger
import dev.chirpboard.app.core.transcription.TranscriptionEngine
import dev.chirpboard.app.core.transcription.TranscriptionRoutingStore
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

class SpeechModelReadinessCoordinatorTest {
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var readinessGate: SpeechModelReadinessGate
    private lateinit var coordinator: SpeechModelReadinessCoordinator
    private lateinit var transcriptionRoutingStore: TranscriptionRoutingStore

    @Before
    fun setup() {
        recordingRepository = mockk()
        readinessGate = mockk(relaxed = true)
        transcriptionRoutingStore = mockk()
        coEvery { transcriptionRoutingStore.getSelectedEngine() } returns TranscriptionEngine.LOCAL_PARAKEET
        coordinator = SpeechModelReadinessCoordinator(recordingRepository, readinessGate, transcriptionRoutingStore)
    }

    @Test
    fun `idle startup skips speech model verification`() = runTest {
        coEvery { recordingRepository.getPendingRecordings() } returns emptyList()
        every { recordingRepository.getRecordingsByStatus(RecordingStatus.FAILED) } returns
            flowOf(RepositoryFlowState(emptyList()))

        coordinator.verifyOnAppStartupIfCandidate()

        verify(exactly = 0) { readinessGate.verifyIfNeeded(any()) }
    }

    @Test
    fun `queued transcription startup verifies the queued candidate`() = runTest {
        coEvery { recordingRepository.getPendingRecordings() } returns
            listOf(recording(status = RecordingStatus.PENDING_TRANSCRIPTION))

        coordinator.verifyOnAppStartupIfCandidate()

        verify { readinessGate.verifyIfNeeded(VerificationTrigger.QUEUED_TRANSCRIPTION) }
    }

    @Test
    fun `recovery startup verifies the recovery candidate`() = runTest {
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

        coordinator.verifyOnAppStartupIfCandidate()

        verify { readinessGate.verifyIfNeeded(VerificationTrigger.RECOVERY) }
    }

    @Test
    fun `cloud-only startup skips speech model verification`() = runTest {
        coEvery { recordingRepository.getPendingRecordings() } returns
            listOf(
                recording(
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                    transcriptionEngine = TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3,
                ),
            )
        every { recordingRepository.getRecordingsByStatus(RecordingStatus.FAILED) } returns
            flowOf(RepositoryFlowState(emptyList()))

        coordinator.verifyOnAppStartupIfCandidate()

        verify(exactly = 0) { readinessGate.verifyIfNeeded(any()) }
    }

    private fun recording(
        status: RecordingStatus,
        errorMessage: String? = null,
        transcriptionEngine: TranscriptionEngine = TranscriptionEngine.LOCAL_PARAKEET,
    ): Recording =
        Recording(
            id = UUID.randomUUID(),
            title = "Test",
            audioPath = "/tmp/test.m4a",
            source = RecordingSource.APP,
            status = status,
            errorMessage = errorMessage,
            transcriptionEngineId = transcriptionEngine.id,
        )
}
