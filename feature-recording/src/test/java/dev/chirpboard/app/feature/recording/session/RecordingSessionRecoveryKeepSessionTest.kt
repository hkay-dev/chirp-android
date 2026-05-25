package dev.chirpboard.app.feature.recording.session

import android.content.Context
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.data.repository.RecordingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class RecordingSessionRecoveryKeepSessionTest {
    private lateinit var context: Context
    private lateinit var journal: RecordingSessionJournal
    private lateinit var protectedPathsStore: RecordingRecoveryProtectedPathsStore
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var sessionRecovery: RecordingSessionRecovery

    @Before
    fun setup() {
        val root = createTempDir("keep-session-test")
        context =
            mockk(relaxed = true) {
                every { filesDir } returns root
            }
        journal = RecordingSessionJournal(context)
        recordingRepository = mockk(relaxed = true)
        protectedPathsStore =
            mockk(relaxed = true) {
                coEvery { protect(any()) } returns Unit
            }
        sessionRecovery =
            RecordingSessionRecovery(
                sessionJournal = journal,
                recordingRepository = recordingRepository,
                transcriptionRecovery = mockk(relaxed = true),
                fileValidator = mockk(relaxed = true),
                segmentFinalize = mockk(relaxed = true),
                capturePaths = RecordingCapturePaths(context),
                sessionReconciler =
                    RecordingSessionReconciler(
                        sessionJournal = journal,
                        recordingRepository = mockk(relaxed = true),
                        capturePaths = RecordingCapturePaths(context),
                    ),
                recordingStateManager = mockk(relaxed = true),
                protectedPathsStore = protectedPathsStore,
            )
    }

    @Test
    fun keepSession_protectsPathsRemovesJournalAndDeletesInProgressRow() =
        runTest {
            val sessionId = UUID.randomUUID()
            val recordingId = UUID.randomUUID()
            val audioFile =
                File(context.filesDir, "recordings/recording_keep.m4a").apply {
                    parentFile?.mkdirs()
                    writeText("audio")
                }

            journal.createSession(
                sessionId = sessionId,
                audioPath = audioFile.absolutePath,
                origin = RecordingOrigin.APP,
                profileId = null,
                recordingId = recordingId,
                correlationId = "corr",
            )

            sessionRecovery.keepSession(sessionId)

            coVerify { protectedPathsStore.protect(any()) }
            coVerify { recordingRepository.deleteInProgressRecording(recordingId) }
            assertNull(journal.findBySessionId(sessionId))
            assertTrue(audioFile.exists())
        }
}
