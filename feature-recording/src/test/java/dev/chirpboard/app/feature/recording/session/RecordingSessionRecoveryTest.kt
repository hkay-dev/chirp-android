package dev.chirpboard.app.feature.recording.session

import android.content.Context
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
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
import kotlinx.coroutines.flow.MutableStateFlow
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
    private lateinit var segmentFinalize: RecordingSegmentFinalize
    private lateinit var recordingStateManager: RecordingStateManager
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
        every { fileValidator.validateForStop(any()) } returns
            RecordingFileValidation(RecordingValidationLevel.PLAYABLE)
        segmentFinalize = mockk(relaxed = true)
        recordingStateManager = mockk(relaxed = true)
        every { recordingStateManager.state } returns MutableStateFlow(RecordingState.Idle)

        mockkObject(ReliabilityEventLogger)
        every { ReliabilityEventLogger.newCorrelationId(any()) } returns "corr-recover"
        every { ReliabilityEventLogger.log(any(), any(), any(), any(), any(), any()) } just runs

        sessionRecovery =
            RecordingSessionRecovery(
                sessionJournal = journal,
                recordingRepository = recordingRepository,
                transcriptionRecovery = transcriptionRecovery,
                fileValidator = fileValidator,
                segmentFinalize = segmentFinalize,
                capturePaths = RecordingCapturePaths(context),
                sessionReconciler =
                    RecordingSessionReconciler(
                        sessionJournal = journal,
                        recordingRepository = recordingRepository,
                        capturePaths = RecordingCapturePaths(context),
                    ),
                recordingStateManager = recordingStateManager,
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

            assertTrue(result.toString(), result is SessionRecoveryResult.Recovered)
            assertEquals(recordingId, (result as SessionRecoveryResult.Recovered).recordingId)
            assertNull(journal.findBySessionId(sessionId))
            coVerify(exactly = 0) { recordingRepository.finalizeInProgressRecording(any(), any(), any()) }
            coVerify(exactly = 0) { recordingRepository.createRecording(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { transcriptionRecovery.enqueue(any(), any()) }
        }

    @Test
    fun recoverSession_missingLinkedRowCreatesReplacementRecording() =
        runTest {
            val sessionId = UUID.randomUUID()
            val recordingId = UUID.randomUUID()
            val replacementId = UUID.randomUUID()
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
            coEvery { recordingRepository.finalizeInProgressRecording(recordingId, any(), any()) } returns null
            coEvery {
                recordingRepository.createRecording(any(), any(), any(), any(), any())
            } returns
                Recording(
                    id = replacementId,
                    title = "Recovered",
                    audioPath = audioFile.absolutePath,
                    source = RecordingSource.APP,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                    createdAt = Date(),
                )

            val result = sessionRecovery.recoverSession(sessionId)

            assertTrue(result.toString(), result is SessionRecoveryResult.Recovered)
            assertEquals(replacementId, (result as SessionRecoveryResult.Recovered).recordingId)
            assertNull(journal.findBySessionId(sessionId))
            coVerify(exactly = 0) { recordingRepository.finalizeInProgressRecording(any(), any(), any()) }
            coVerify { recordingRepository.createRecording(any(), audioFile.absolutePath, RecordingSource.APP, null, any()) }
            coVerify { transcriptionRecovery.enqueue(replacementId, "corr-2") }
        }

    @Test
    fun scanForRecoverableSessions_includesAbandonedSessionWithFinalAudio() =
        runTest {
            val sessionId = UUID.randomUUID()
            val activeSegment = File(context.filesDir, "recordings/.capture/$sessionId/seg-000.m4a")
            val finalFile = createRecoverableAudioFile("abandoned-final.m4a")

            journal.createSession(
                sessionId = sessionId,
                audioPath = activeSegment.absolutePath,
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = UUID.randomUUID(),
                correlationId = "corr-4",
                finalAudioPath = finalFile.absolutePath,
            )
            journal.markAbandoned(sessionId)

            val sessions = sessionRecovery.scanForRecoverableSessions()

            assertEquals(listOf(sessionId), sessions.map { it.sessionId })
            assertEquals(finalFile.absolutePath, sessions.single().audioPath)
        }

    @Test
    fun recoverSession_abandonedSessionWithFinalAudioCreatesRecording() =
        runTest {
            val sessionId = UUID.randomUUID()
            val linkedRecordingId = UUID.randomUUID()
            val recoveredRecordingId = UUID.randomUUID()
            val activeSegment = File(context.filesDir, "recordings/.capture/$sessionId/seg-000.m4a")
            val finalFile = createRecoverableAudioFile("abandoned-final-recover.m4a")

            journal.createSession(
                sessionId = sessionId,
                audioPath = activeSegment.absolutePath,
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = linkedRecordingId,
                correlationId = "corr-5",
                finalAudioPath = finalFile.absolutePath,
            )
            journal.markAbandoned(sessionId)

            coEvery { recordingRepository.getRecording(linkedRecordingId) } returns null
            coEvery { recordingRepository.finalizeInProgressRecording(linkedRecordingId, any(), any()) } returns null
            every { segmentFinalize.materializeExportFile(sessionId, activeSegment.absolutePath) } returns finalFile
            coEvery {
                recordingRepository.createRecording(any(), any(), any(), any(), any())
            } returns
                Recording(
                    id = recoveredRecordingId,
                    title = "Recovered",
                    audioPath = finalFile.absolutePath,
                    source = RecordingSource.APP,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                    createdAt = Date(),
                )

            val result = sessionRecovery.recoverSession(sessionId)

            assertTrue(result is SessionRecoveryResult.Recovered)
            assertEquals(recoveredRecordingId, (result as SessionRecoveryResult.Recovered).recordingId)
            assertNull(journal.findBySessionId(sessionId))
            coVerify { recordingRepository.createRecording(any(), finalFile.absolutePath, RecordingSource.APP, null, any()) }
            coVerify { transcriptionRecovery.enqueue(recoveredRecordingId, "corr-5") }
        }

    @Test
    fun recoverDurableStoppedSessions_recoversAbandonedFinalAudioAutomatically() =
        runTest {
            val sessionId = UUID.randomUUID()
            val linkedRecordingId = UUID.randomUUID()
            val recoveredRecordingId = UUID.randomUUID()
            val activeSegment = File(context.filesDir, "recordings/.capture/$sessionId/seg-000.m4a")
            val finalFile = createRecoverableAudioFile("abandoned-auto-recover.m4a")

            journal.createSession(
                sessionId = sessionId,
                audioPath = activeSegment.absolutePath,
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = linkedRecordingId,
                correlationId = "corr-6",
                finalAudioPath = finalFile.absolutePath,
            )
            journal.markAbandoned(sessionId)

            coEvery { recordingRepository.getRecording(linkedRecordingId) } returns null
            every { segmentFinalize.materializeExportFile(sessionId, activeSegment.absolutePath) } returns finalFile
            coEvery {
                recordingRepository.createRecording(any(), any(), any(), any(), any())
            } returns
                Recording(
                    id = recoveredRecordingId,
                    title = "Recovered",
                    audioPath = finalFile.absolutePath,
                    source = RecordingSource.APP,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                    createdAt = Date(),
                )

            sessionRecovery.recoverDurableStoppedSessions()

            assertNull(journal.findBySessionId(sessionId))
            coVerify { recordingRepository.createRecording(any(), finalFile.absolutePath, RecordingSource.APP, null, any()) }
            coVerify { transcriptionRecovery.enqueue(recoveredRecordingId, "corr-6") }
        }

    @Test
    fun recoverSession_stoppingJournalFinalizesLinkedRecordingRow() =
        runTest {
            val sessionId = UUID.randomUUID()
            val recordingId = UUID.randomUUID()
            val audioFile = createRecoverableAudioFile("linked-row.m4a")

            journal.createSession(
                sessionId = sessionId,
                audioPath = audioFile.absolutePath,
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = recordingId,
                correlationId = "corr-3",
            )
            journal.markStopping(sessionId)

            val inProgress =
                Recording(
                    id = recordingId,
                    title = "In progress",
                    audioPath = audioFile.absolutePath,
                    source = RecordingSource.APP,
                    status = RecordingStatus.RECORDING,
                    createdAt = Date(),
                )
            val finalized = inProgress.copy(status = RecordingStatus.PENDING_TRANSCRIPTION)
            coEvery { recordingRepository.getRecording(recordingId) } returns inProgress
            coEvery {
                recordingRepository.finalizeInProgressRecording(recordingId, any(), any())
            } returns finalized

            val result = sessionRecovery.recoverSession(sessionId)

            assertTrue(result is SessionRecoveryResult.Recovered)
            assertEquals(recordingId, (result as SessionRecoveryResult.Recovered).recordingId)
            assertNull(journal.findBySessionId(sessionId))
            coVerify { recordingRepository.finalizeInProgressRecording(recordingId, any(), any()) }
            coVerify(exactly = 0) { recordingRepository.createRecording(any(), any(), any(), any(), any()) }
            coVerify { transcriptionRecovery.enqueue(recordingId, "corr-3") }
        }

    private fun createRecoverableAudioFile(name: String): File =
        File(context.filesDir, "recordings/$name").apply {
            parentFile?.mkdirs()
            writeText("x".repeat(RecordingSessionJournal.MIN_RECOVERABLE_FILE_BYTES.toInt()))
        }
}
