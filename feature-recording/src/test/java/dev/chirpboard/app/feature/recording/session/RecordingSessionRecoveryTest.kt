package dev.chirpboard.app.feature.recording.session

import android.content.Context
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.session.validation.RecordingFileValidation
import dev.chirpboard.app.feature.recording.session.validation.RecordingFileValidator
import dev.chirpboard.app.feature.recording.session.validation.RecordingValidationLevel
import dev.chirpboard.app.feature.recording.service.RecordingSegmentFinalize
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Date
import java.util.UUID

class RecordingSessionRecoveryTest {
    private lateinit var context: Context
    private lateinit var journal: RecordingSessionJournal
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var transcriptionRecovery: TranscriptionRecovery
    private lateinit var fileValidator: RecordingFileValidator
    private lateinit var sessionRecovery: RecordingSessionRecovery

    @Before
    fun setup() {
        val root = createTempDir("recovery-test")
        context =
            mockk(relaxed = true) {
                every { filesDir } returns root
            }
        journal = RecordingSessionJournal(context)
        recordingRepository = mockk(relaxed = true)
        transcriptionRecovery = mockk(relaxed = true)
        fileValidator = mockk(relaxed = true)
        every { fileValidator.validateForRecovery(any()) } returns
            RecordingFileValidation(RecordingValidationLevel.PLAYABLE)

        mockkObject(ReliabilityEventLogger)
        every { ReliabilityEventLogger.newCorrelationId(any()) } returns "corr-recover"
        every { ReliabilityEventLogger.log(any(), any(), any(), any(), any(), any()) } just runs

        sessionRecovery =
            RecordingSessionRecovery(
                sessionJournal = journal,
                recordingRepository = recordingRepository,
                transcriptionRecovery = transcriptionRecovery,
                fileValidator = fileValidator,
                segmentFinalize = mockk<RecordingSegmentFinalize>(relaxed = true),
                capturePaths = RecordingCapturePaths(context),
                sessionReconciler =
                    RecordingSessionReconciler(
                        sessionJournal = journal,
                        recordingRepository = recordingRepository,
                        capturePaths = RecordingCapturePaths(context),
                    ),
                recordingStateManager = mockk(relaxed = true),
                protectedPathsStore = mockk(relaxed = true),
            )
    }

    @After
    fun tearDown() {
        unmockkObject(ReliabilityEventLogger)
    }

    @Test
    fun recoverSession_alreadyFinalized_doesNotDuplicateRowOrEnqueue() =
        runTest {
            val sessionId = UUID.randomUUID()
            val recordingId = UUID.randomUUID()
            val audioFile = createRecoverableAudioFile("already-finalized.m4a")

            journal.createSession(
                sessionId = sessionId,
                audioPath = audioFile.absolutePath,
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = recordingId,
                correlationId = "corr-1",
            )

            coEvery { recordingRepository.getRecording(recordingId) } returns
                Recording(
                    id = recordingId,
                    title = "Saved",
                    audioPath = audioFile.absolutePath,
                    source = RecordingSource.APP,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                    createdAt = Date(),
                )

            val result = sessionRecovery.recoverSession(sessionId)

            assertTrue(result is SessionRecoveryResult.Recovered)
            assertEquals(recordingId, (result as SessionRecoveryResult.Recovered).recordingId)
            assertNull(journal.findBySessionId(sessionId))
            coVerify(exactly = 0) { recordingRepository.finalizeInProgressRecording(any(), any(), any()) }
            coVerify(exactly = 0) { recordingRepository.createRecording(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { transcriptionRecovery.enqueue(any(), any()) }
        }

    @Test
    fun recoverSession_missingLinkedRow_abandonsJournalAndFails() =
        runTest {
            val sessionId = UUID.randomUUID()
            val recordingId = UUID.randomUUID()
            val audioFile = createRecoverableAudioFile("missing-row.m4a")

            journal.createSession(
                sessionId = sessionId,
                audioPath = audioFile.absolutePath,
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = recordingId,
                correlationId = "corr-2",
            )

            coEvery { recordingRepository.getRecording(recordingId) } returns null

            val result = sessionRecovery.recoverSession(sessionId)

            assertTrue(result is SessionRecoveryResult.Failed)
            assertTrue(journal.loadRecoverableSessions().isEmpty())
            coVerify(exactly = 0) { recordingRepository.finalizeInProgressRecording(any(), any(), any()) }
            coVerify(exactly = 0) { recordingRepository.createRecording(any(), any(), any(), any(), any()) }
        }

    private fun createRecoverableAudioFile(name: String): File =
        File(context.filesDir, "recordings/$name").apply {
            parentFile?.mkdirs()
            writeText("x".repeat(RecordingSessionJournal.MIN_RECOVERABLE_FILE_BYTES.toInt()))
        }
}
