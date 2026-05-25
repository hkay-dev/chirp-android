package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.session.RecordingSessionJournal
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class StopSnapshotWorkDataTest {
    @Test
    fun stopSnapshot_roundTripsThroughWorkData() {
        val recordingId = UUID.randomUUID()
        val profileId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val original =
            StopSnapshot(
                origin = RecordingOrigin.APP,
                profileId = profileId,
                recordingId = recordingId,
                audioFilePath = "/tmp/active.m4a",
                durationMs = 12_000L,
                stoppedAtEpochMs = 1_700_000_000_000L,
                wasPaused = true,
                correlationId = "corr-test",
            )

        val restored = StopSnapshot.fromWorkData(original.toWorkData(sessionId))

        assertEquals(original.origin, restored?.origin)
        assertEquals(original.profileId, restored?.profileId)
        assertEquals(original.recordingId, restored?.recordingId)
        assertEquals(original.audioFilePath, restored?.audioFilePath)
        assertEquals(original.durationMs, restored?.durationMs)
        assertEquals(original.stoppedAtEpochMs, restored?.stoppedAtEpochMs)
        assertEquals(original.wasPaused, restored?.wasPaused)
        assertEquals(original.correlationId, restored?.correlationId)
    }
}

class RecordingFinalizeStopOutcomeApplierTest {
    @Test
    fun persistenceFailed_deletesInProgressRow() =
        runTest {
            val recordingId = UUID.randomUUID()
            val sessionId = UUID.randomUUID()
            val sessionJournal = mockk<RecordingSessionJournal>(relaxed = true)
            val recordingRepository = mockk<RecordingRepository>(relaxed = true)

            RecordingFinalizeStopOutcomeApplier.apply(
                result = StopPersistenceResult.PersistenceFailed("failed"),
                snapshot =
                    StopSnapshot(
                        origin = RecordingOrigin.APP,
                        profileId = null,
                        recordingId = recordingId,
                        audioFilePath = "/tmp/active.m4a",
                        durationMs = 0L,
                        stoppedAtEpochMs = 0L,
                        wasPaused = false,
                        correlationId = "corr",
                    ),
                sessionId = sessionId,
                sessionJournal = sessionJournal,
                recordingRepository = recordingRepository,
            )

            coVerify { sessionJournal.markAbandoned(sessionId) }
            coVerify { recordingRepository.deleteInProgressRecording(recordingId) }
        }
}
