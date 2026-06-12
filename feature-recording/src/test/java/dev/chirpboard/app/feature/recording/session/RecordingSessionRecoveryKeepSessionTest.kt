package dev.chirpboard.app.feature.recording.session

import android.content.Context
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.service.RecordingFinalizeWorkRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import dev.chirpboard.app.feature.recording.util.RecordingTitleFormatter
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class RecordingSessionRecoveryKeepSessionTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

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
        // Ownership checks fail closed on query errors, so tests must answer the
        // finalize-work query explicitly instead of relying on a swallowed throw.
        mockkObject(RecordingFinalizeWorkRequest)
        coEvery { RecordingFinalizeWorkRequest.hasUnfinishedWork(any(), any()) } returns false
        sessionRecovery =
            RecordingSessionRecovery(
                context = context,
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
                ownershipLock = RecordingFinalizeOwnershipLock(),
                titleFormatter =
                    mockk<RecordingTitleFormatter> {
                        every { format(any()) } returns "Jun 12, 3:42 PM"
                    },
            )
    }

    @After
    fun tearDown() {
        unmockkObject(RecordingFinalizeWorkRequest)
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
            coVerify { recordingRepository.deleteAbandonedInProgressRecording(recordingId) }
            assertNull(journal.findBySessionId(sessionId))
            assertTrue(audioFile.exists())
        }

    @Test
    fun keepSession_refusesWhileFinalizeWorkerOwnsSession() =
        runTest {
            val sessionId = UUID.randomUUID()
            val recordingId = UUID.randomUUID()
            val audioFile =
                File(context.filesDir, "recordings/recording_keep_owned.m4a").apply {
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

            mockkObject(RecordingFinalizeWorkRequest)
            try {
                coEvery { RecordingFinalizeWorkRequest.hasUnfinishedWork(any(), recordingId) } returns true

                val result = sessionRecovery.keepSession(sessionId)

                assertTrue(result.toString(), result is SessionRecoveryResult.Failed)
            } finally {
                unmockkObject(RecordingFinalizeWorkRequest)
            }

            // The worker still owns the recording row and journal entry.
            assertTrue(journal.findBySessionId(sessionId) != null)
            coVerify(exactly = 0) { recordingRepository.deleteAbandonedInProgressRecording(any()) }
        }
}
