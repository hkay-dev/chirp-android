package dev.chirpboard.app.feature.recording.session

import android.content.Context
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.feature.recording.session.validation.RecordingFileValidation
import dev.chirpboard.app.feature.recording.session.validation.RecordingFileValidator
import dev.chirpboard.app.feature.recording.session.validation.RecordingValidationLevel
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.service.RecordingSegmentFinalize
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Date
import java.util.UUID

class RecordingSessionRecoveryLiveSessionTest {
    private lateinit var context: Context
    private lateinit var journal: RecordingSessionJournal
    private lateinit var recordingStateManager: RecordingStateManager
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var fileValidator: RecordingFileValidator
    private lateinit var sessionRecovery: RecordingSessionRecovery

    @Before
    fun setup() {
        val root = createTempDir("recovery-live-test")
        context =
            mockk(relaxed = true) {
                every { filesDir } returns root
            }
        journal = RecordingSessionJournal(context)
        recordingStateManager = mockk(relaxed = true)
        recordingRepository = mockk(relaxed = true)
        fileValidator = mockk(relaxed = true)
        every { fileValidator.validateForStop(any()) } returns
            RecordingFileValidation(RecordingValidationLevel.PLAYABLE)
        every { fileValidator.validateForRecovery(any()) } returns
            RecordingFileValidation(RecordingValidationLevel.PLAYABLE)
        sessionRecovery =
            RecordingSessionRecovery(
                sessionJournal = journal,
                recordingRepository = recordingRepository,
                transcriptionRecovery = mockk(relaxed = true),
                fileValidator = fileValidator,
                segmentFinalize = mockk<RecordingSegmentFinalize>(relaxed = true),
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

    @Test
    fun scanForRecoverableSessions_excludesCurrentlyActiveRecording() =
        runTest {
            val sessionId = UUID.randomUUID()
            val recordingId = UUID.randomUUID()
            val segmentPath =
                File(context.filesDir, "recordings/.capture/$sessionId/seg-000.m4a").apply {
                    parentFile?.mkdirs()
                    writeText("x".repeat(RecordingSessionJournal.MIN_RECOVERABLE_FILE_BYTES.toInt()))
                }

            journal.createSession(
                sessionId = sessionId,
                audioPath = segmentPath.absolutePath,
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = recordingId,
                correlationId = "corr-1",
            )

            coEvery { recordingRepository.getRecording(recordingId) } returns inProgressRecording(recordingId, segmentPath.absolutePath)

            every { recordingStateManager.state } returns
                MutableStateFlow(
                    RecordingState.Recording(
                        origin = RecordingOrigin.APP,
                        recordingId = recordingId,
                    ),
                )

            val recoverable = sessionRecovery.scanForRecoverableSessions()

            assertTrue(recoverable.isEmpty())
        }

    @Test
    fun scanForRecoverableSessions_includesInterruptedSessionWhenIdle() =
        runTest {
            val sessionId = UUID.randomUUID()
            val recordingId = UUID.randomUUID()
            val segmentPath =
                File(context.filesDir, "recordings/.capture/$sessionId/seg-000.m4a").apply {
                    parentFile?.mkdirs()
                    writeText("x".repeat(RecordingSessionJournal.MIN_RECOVERABLE_FILE_BYTES.toInt()))
                }

            journal.createSession(
                sessionId = sessionId,
                audioPath = segmentPath.absolutePath,
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = recordingId,
                correlationId = "corr-1",
            )

            coEvery { recordingRepository.getRecording(recordingId) } returns inProgressRecording(recordingId, segmentPath.absolutePath)

            every { recordingStateManager.state } returns MutableStateFlow(RecordingState.Idle)

            val recoverable = sessionRecovery.scanForRecoverableSessions()

            assertEquals(1, recoverable.size)
            assertEquals(sessionId, recoverable.first().sessionId)
        }

    private fun inProgressRecording(
        recordingId: UUID,
        audioPath: String,
    ): Recording =
        Recording(
            id = recordingId,
            title = "In progress",
            audioPath = audioPath,
            source = RecordingSource.APP,
            status = RecordingStatus.RECORDING,
            createdAt = Date(),
        )
}
