package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.session.RecordingSessionJournal
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class RecordingServiceStopOutcomesTest {
    private lateinit var sessionJournal: RecordingSessionJournal
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var recordingStateManager: RecordingStateManager
    private val stopGeneration = AtomicInteger(0)

    @Before
    fun setup() {
        sessionJournal = mockk(relaxed = true)
        recordingRepository = mockk(relaxed = true)
        recordingStateManager = mockk(relaxed = true)
        coEvery { recordingRepository.deleteAbandonedInProgressRecording(any()) } just runs

        mockkObject(ReliabilityEventLogger)
        every { ReliabilityEventLogger.log(any(), any(), any(), any(), any(), any()) } just runs
        every { ReliabilityEventLogger.newCorrelationId(any()) } returns "corr-test"
    }

    @After
    fun tearDown() {
        unmockkObject(ReliabilityEventLogger)
    }

    @Test
    fun noAudioFileWithoutArtifacts_deletesInProgressRowAndCompletes() = runTest {
        val recordingId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val snapshot =
            StopSnapshot(
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = recordingId,
                audioFilePath = null,
                durationMs = 0L,
                stoppedAtEpochMs = 0L,
                wasPaused = false,
                correlationId = "corr",
            )
        val generation = stopGeneration.incrementAndGet()

        val result =
            RecordingServiceStopOutcomeApplier.apply(
                result = StopPersistenceResult.NoAudioFile,
                snapshot = snapshot,
                sessionId = sessionId,
                generation = generation,
                stopGeneration = stopGeneration,
                sessionJournal = sessionJournal,
                recordingRepository = recordingRepository,
                recordingStateManager = recordingStateManager,
                refreshRecovery = {},
            )

        assertEquals(StopOutcomeApplyResult.Applied, result)
        verify { sessionJournal.markAbandoned(sessionId) }
        coVerify(exactly = 1) { recordingRepository.deleteAbandonedInProgressRecording(recordingId) }
        verify { recordingStateManager.onRecordingCompleted() }
        verify(exactly = 0) { recordingStateManager.onRecordingError(any(), any()) }
    }

    @Test
    fun persistenceFailedWithArtifacts_keepsInProgressRowAndJournalRecoverable() = runTest {
        val recordingId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val audioFile = java.io.File.createTempFile("recoverable-stop", ".m4a")
        val snapshot =
            StopSnapshot(
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = recordingId,
                audioFilePath = audioFile.absolutePath,
                durationMs = 0L,
                stoppedAtEpochMs = 0L,
                wasPaused = false,
                correlationId = "corr",
            )
        val generation = stopGeneration.incrementAndGet()

        val result =
            RecordingServiceStopOutcomeApplier.apply(
                result = StopPersistenceResult.PersistenceFailed("failed"),
                snapshot = snapshot,
                sessionId = sessionId,
                generation = generation,
                stopGeneration = stopGeneration,
                sessionJournal = sessionJournal,
                recordingRepository = recordingRepository,
                recordingStateManager = recordingStateManager,
                refreshRecovery = {},
            )

        assertEquals(StopOutcomeApplyResult.Applied, result)
        verify(exactly = 0) { sessionJournal.markAbandoned(sessionId) }
        coVerify(exactly = 0) { recordingRepository.deleteAbandonedInProgressRecording(recordingId) }
        verify { recordingStateManager.onRecordingError("failed", null) }
        audioFile.delete()
    }

    @Test
    fun staleGeneration_discardsPersistResultWithoutStateTransition() = runTest {
        val recordingId = UUID.randomUUID()
        val snapshot =
            StopSnapshot(
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = recordingId,
                audioFilePath = "/tmp/test.m4a",
                durationMs = 1000L,
                stoppedAtEpochMs = 0L,
                wasPaused = false,
                correlationId = "corr",
            )
        val generation = stopGeneration.incrementAndGet()
        stopGeneration.incrementAndGet()

        val result =
            RecordingServiceStopOutcomeApplier.apply(
                result = StopPersistenceResult.SavedAndQueued(recordingId),
                snapshot = snapshot,
                sessionId = UUID.randomUUID(),
                generation = generation,
                stopGeneration = stopGeneration,
                sessionJournal = sessionJournal,
                recordingRepository = recordingRepository,
                recordingStateManager = recordingStateManager,
                refreshRecovery = {},
            )

        assertEquals(StopOutcomeApplyResult.StaleGeneration, result)
        verify(exactly = 0) { recordingStateManager.onRecordingCompleted(any()) }
        verify(exactly = 0) { recordingStateManager.onRecordingError(any(), any()) }
        coVerify(exactly = 0) { recordingRepository.deleteAbandonedInProgressRecording(any()) }
        verify {
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.RECORDING_STOP,
                outcome = ReliabilityOutcome.SKIPPED,
                correlationId = "corr",
                reasonCode = "stop_generation_stale",
            )
        }
    }

    @Test
    fun savedAndQueued_completesWhenGenerationMatches() = runTest {
        val recordingId = UUID.randomUUID()
        val snapshot =
            StopSnapshot(
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = recordingId,
                audioFilePath = "/tmp/test.m4a",
                durationMs = 1000L,
                stoppedAtEpochMs = 0L,
                wasPaused = false,
                correlationId = "corr",
            )
        val generation = stopGeneration.incrementAndGet()

        val result =
            RecordingServiceStopOutcomeApplier.apply(
                result = StopPersistenceResult.SavedAndQueued(recordingId),
                snapshot = snapshot,
                sessionId = UUID.randomUUID(),
                generation = generation,
                stopGeneration = stopGeneration,
                sessionJournal = sessionJournal,
                recordingRepository = recordingRepository,
                recordingStateManager = recordingStateManager,
                refreshRecovery = {},
            )

        assertEquals(StopOutcomeApplyResult.Applied, result)
        verify { recordingStateManager.onRecordingCompleted(recordingId = recordingId) }
    }
}
