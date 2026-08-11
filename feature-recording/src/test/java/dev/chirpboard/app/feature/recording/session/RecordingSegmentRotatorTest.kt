package dev.chirpboard.app.feature.recording.session

import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.feature.recording.service.GaplessSegmentCaptureEngine
import dev.chirpboard.app.feature.recording.service.SegmentRotationResult
import dev.chirpboard.app.feature.recording.service.StopRequestGate
import dev.chirpboard.app.feature.recording.session.validation.RecordingFileValidation
import dev.chirpboard.app.feature.recording.session.validation.RecordingFileValidator
import dev.chirpboard.app.feature.recording.session.validation.RecordingValidationLevel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class RecordingSegmentRotatorTest {
    @get:Rule
    val logRule = MockAndroidLogRule()

    private val sessionId: UUID = UUID.randomUUID()
    private val completedFile = File(createTempDir("rotator-test"), "seg-000.wav")
    private val nextSegmentFile = File(completedFile.parentFile, "seg-001.wav")

    private val journal =
        mockk<RecordingSessionJournal>(relaxed = true) {
            every { findBySessionId(sessionId) } returns
                RecordingSessionEntry(
                    sessionId = sessionId,
                    audioPath = completedFile.absolutePath,
                    finalAudioPath = null,
                    segmentPaths = emptyList(),
                    origin = RecordingOrigin.APP,
                    profileId = null,
                    recordingId = null,
                    startedAtEpochMs = 0L,
                    lastHeartbeatEpochMs = 0L,
                    lastSegmentFinalizedAtEpochMs = null,
                    activeSegmentStartedAtEpochMs = 0L,
                    fileBytes = 0L,
                    checkpointPath = null,
                    state = SessionJournalState.ACTIVE,
                    correlationId = null,
                )
        }
    private val capturePaths =
        mockk<RecordingCapturePaths> {
            every { durableSegmentFile(sessionId, 1) } returns nextSegmentFile
        }
    private val validator = mockk<RecordingFileValidator>()
    private val engine =
        mockk<GaplessSegmentCaptureEngine> {
            every { rotateSegment(nextSegmentFile) } returns SegmentRotationResult.Success
        }
    // Lazy so construction happens inside the test, after MockAndroidLogRule applies.
    private val stateManager by lazy {
        RecordingStateManager().apply {
            tryStartRecording(RecordingOrigin.APP, profileId = null)
            onRecordingStarted(audioFilePath = completedFile.absolutePath)
        }
    }

    private suspend fun rotate(): SegmentRotationOutcome? =
        RecordingSegmentRotator(journal, capturePaths, validator).rotateIfNeeded(
            recordingStateManager = stateManager,
            stopRequestGate = StopRequestGate(),
            segmentTransitionMutex = Mutex(),
            sessionId = sessionId,
            segmentCapture = engine,
            currentRecordingFile = completedFile,
            correlationId = "test",
        )

    @Test
    fun `invalid completed segment still records the engine's switch to the next segment`() =
        runTest {
            // The engine has already moved on once rotateSegment succeeds. Skipping the
            // journal append here made the next tick reuse the same segment index and
            // truncate the file the engine was actively writing.
            every { validator.validateForRecovery(completedFile) } returns
                RecordingFileValidation(RecordingValidationLevel.INVALID, "too small")

            val outcome = rotate()

            assertNotNull(outcome)
            assertEquals(nextSegmentFile, outcome?.nextSegmentFile)
            verify {
                journal.appendCompletedSegment(
                    sessionId = sessionId,
                    completedSegmentPath = completedFile.absolutePath,
                    nextSegmentPath = nextSegmentFile.absolutePath,
                    fileBytes = any(),
                )
            }
            val state = stateManager.state.value
            assertEquals(
                nextSegmentFile.absolutePath,
                (state as RecordingState.Recording).audioFilePath,
            )
        }

    @Test
    fun `failed engine rotation appends nothing`() =
        runTest {
            every { engine.rotateSegment(nextSegmentFile) } returns
                SegmentRotationResult.Failed("Capture not running")
            every { validator.validateForRecovery(any()) } returns
                RecordingFileValidation(RecordingValidationLevel.RECOVERABLE_STUB)

            val outcome = rotate()

            assertNull(outcome)
            verify(exactly = 0) { journal.appendCompletedSegment(any(), any(), any(), any()) }
        }
}
