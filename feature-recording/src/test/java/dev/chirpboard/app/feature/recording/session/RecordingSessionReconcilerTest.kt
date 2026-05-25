package dev.chirpboard.app.feature.recording.session

import android.content.Context
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Date
import java.util.UUID

class RecordingSessionReconcilerTest {
    private lateinit var context: Context
    private lateinit var journal: RecordingSessionJournal
    private lateinit var capturePaths: RecordingCapturePaths
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var reconciler: RecordingSessionReconciler

    @Before
    fun setup() {
        val root = createTempDir("reconciler-test")
        context =
            mockk(relaxed = true) {
                every { filesDir } returns root
            }
        journal = RecordingSessionJournal(context)
        capturePaths = RecordingCapturePaths(context)
        recordingRepository = mockk(relaxed = true)
        reconciler =
            RecordingSessionReconciler(
                sessionJournal = journal,
                recordingRepository = recordingRepository,
                capturePaths = capturePaths,
            )
    }

    @Test
    fun reconcileCompletedSessions_finalizesJournalWhenRecordingRowMissing() =
        runTest {
            val sessionId = UUID.randomUUID()
            val recordingId = UUID.randomUUID()
            val audioPath = File(context.filesDir, "recordings/recording_orphan.m4a").absolutePath
            val captureDir = capturePaths.captureDir(sessionId)
            File(captureDir, "seg-000.m4a").writeText("audio")

            journal.createSession(
                sessionId = sessionId,
                audioPath = audioPath,
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = recordingId,
                correlationId = "corr-orphan",
            )

            coEvery { recordingRepository.getRecording(recordingId) } returns null

            reconciler.reconcileCompletedSessions()

            assertTrue(journal.loadActiveSessions().isEmpty())
            assertFalse(captureDir.exists())
        }

    @Test
    fun reconcileCompletedSessions_removesJournalWhenRecordingIsNoLongerInProgress() =
        runTest {
            val sessionId = UUID.randomUUID()
            val recordingId = UUID.randomUUID()
            val audioPath = File(context.filesDir, "recordings/recording_test.m4a").absolutePath
            val captureDir = capturePaths.captureDir(sessionId)
            File(captureDir, "seg-000.m4a").writeText("audio")

            journal.createSession(
                sessionId = sessionId,
                audioPath = audioPath,
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = recordingId,
                correlationId = "corr-1",
            )

            coEvery { recordingRepository.getRecording(recordingId) } returns
                Recording(
                    id = recordingId,
                    title = "Saved",
                    audioPath = audioPath,
                    source = RecordingSource.APP,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                    createdAt = Date(),
                )

            reconciler.reconcileCompletedSessions()

            assertTrue(journal.loadActiveSessions().isEmpty())
            assertFalse(captureDir.exists())
        }

    @Test
    fun reconcileCompletedSessions_keepsActiveJournalWhileRecordingStillInProgress() =
        runTest {
            val sessionId = UUID.randomUUID()
            val recordingId = UUID.randomUUID()
            val audioPath = File(context.filesDir, "recordings/recording_test.m4a").absolutePath

            journal.createSession(
                sessionId = sessionId,
                audioPath = audioPath,
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = recordingId,
                correlationId = "corr-1",
            )

            coEvery { recordingRepository.getRecording(recordingId) } returns
                Recording(
                    id = recordingId,
                    title = "In progress",
                    audioPath = audioPath,
                    source = RecordingSource.APP,
                    status = RecordingStatus.RECORDING,
                    createdAt = Date(),
                )

            reconciler.reconcileCompletedSessions()

            assertEquals(1, journal.loadActiveSessions().size)
        }
}
