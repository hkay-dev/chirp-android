package dev.chirpboard.app.feature.recording.session

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkContinuation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.service.RecordingFinalizeWorkRequest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Date
import java.util.UUID

class RecordingFinalizeStartupReconcilerTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    private lateinit var context: Context
    private lateinit var journal: RecordingSessionJournal
    private lateinit var recordingRepository: RecordingRepository
    private lateinit var workManager: WorkManager
    private lateinit var reconciler: RecordingFinalizeStartupReconciler

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        journal = mockk(relaxed = true)
        recordingRepository = mockk(relaxed = true)
        workManager = mockk(relaxed = true)
        // WorkManager 2.10 is Kotlin: Kotlin call sites resolve to Companion.getInstance, so the
        // companion must be mocked (the old static-bridge mock no longer intercepts).
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(context) } returns workManager
        every {
            workManager.beginUniqueWork(any(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        } returns mockk<WorkContinuation>(relaxed = true)
        reconciler =
            RecordingFinalizeStartupReconciler(
                context = context,
                sessionJournal = journal,
                recordingRepository = recordingRepository,
                ownershipLock = RecordingFinalizeOwnershipLock(),
            )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `reconcilePendingFinalizations reports session with unfinished finalize work without re-enqueueing`() =
        runTest {
            val sessionId = UUID.randomUUID()
            val recordingId = UUID.randomUUID()
            every { journal.loadAllEntries() } returns
                listOf(
                    RecordingSessionEntry(
                        sessionId = sessionId,
                        audioPath = "/tmp/active.m4a",
                        finalAudioPath = "/tmp/final.m4a",
                        segmentPaths = listOf("/tmp/active.m4a"),
                        origin = RecordingOrigin.APP,
                        profileId = null,
                        recordingId = recordingId,
                        startedAtEpochMs = 1L,
                        lastHeartbeatEpochMs = 2L,
                        lastSegmentFinalizedAtEpochMs = 3L,
                        activeSegmentStartedAtEpochMs = 1L,
                        fileBytes = 4L,
                        checkpointPath = null,
                        state = SessionJournalState.STOPPING,
                        correlationId = "corr",
                    ),
                )
            coEvery { recordingRepository.getRecording(recordingId) } returns
                Recording(
                    id = recordingId,
                    title = "In progress",
                    audioPath = "/tmp/final.m4a",
                    source = RecordingSource.APP,
                    status = RecordingStatus.RECORDING,
                    createdAt = Date(),
                )
            val activeWork =
                mockk<WorkInfo> {
                    every { state } returns WorkInfo.State.RUNNING
                }
            every {
                workManager.getWorkInfosByTag(RecordingFinalizeWorkRequest.workTag(recordingId))
            } returns Futures.immediateFuture(listOf(activeWork))

            val enqueuedSessionIds = reconciler.reconcilePendingFinalizations()

            // The session must be reported as handled so startup recovery excludes it,
            // even though no new work is enqueued for it.
            assertEquals(setOf(sessionId), enqueuedSessionIds)

            verify(exactly = 0) {
                workManager.beginUniqueWork(
                    RecordingFinalizeWorkRequest.FINALIZE_PIPELINE,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    any<OneTimeWorkRequest>(),
                )
            }
        }

    @Test
    fun `reconcilePendingFinalizations reenqueues stopping journal with linked recording row`() =
        runTest {
            val sessionId = UUID.randomUUID()
            val recordingId = UUID.randomUUID()
            every { journal.loadAllEntries() } returns
                listOf(
                    RecordingSessionEntry(
                        sessionId = sessionId,
                        audioPath = "/tmp/active.m4a",
                        finalAudioPath = "/tmp/final.m4a",
                        segmentPaths = listOf("/tmp/active.m4a"),
                        origin = RecordingOrigin.APP,
                        profileId = null,
                        recordingId = recordingId,
                        startedAtEpochMs = 1L,
                        lastHeartbeatEpochMs = 2L,
                        lastSegmentFinalizedAtEpochMs = 3L,
                        activeSegmentStartedAtEpochMs = 1L,
                        fileBytes = 4L,
                        checkpointPath = null,
                        state = SessionJournalState.STOPPING,
                        correlationId = "corr",
                    ),
                )
            coEvery { recordingRepository.getRecording(recordingId) } returns
                Recording(
                    id = recordingId,
                    title = "In progress",
                    audioPath = "/tmp/final.m4a",
                    source = RecordingSource.APP,
                    status = RecordingStatus.RECORDING,
                    createdAt = Date(),
                )
            every {
                workManager.getWorkInfosByTag(RecordingFinalizeWorkRequest.workTag(recordingId))
            } returns Futures.immediateFuture(emptyList())

            val enqueuedSessionIds = reconciler.reconcilePendingFinalizations()

            assertEquals(setOf(sessionId), enqueuedSessionIds)

            verify {
                workManager.beginUniqueWork(
                    RecordingFinalizeWorkRequest.FINALIZE_PIPELINE,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    any<OneTimeWorkRequest>(),
                )
            }
        }

    @Test
    fun `reconcilePendingFinalizations fails closed when the work query hangs`() =
        runTest {
            val sessionId = UUID.randomUUID()
            val recordingId = UUID.randomUUID()
            every { journal.loadAllEntries() } returns
                listOf(
                    RecordingSessionEntry(
                        sessionId = sessionId,
                        audioPath = "/tmp/active.m4a",
                        finalAudioPath = "/tmp/final.m4a",
                        segmentPaths = listOf("/tmp/active.m4a"),
                        origin = RecordingOrigin.APP,
                        profileId = null,
                        recordingId = recordingId,
                        startedAtEpochMs = 1L,
                        lastHeartbeatEpochMs = 2L,
                        lastSegmentFinalizedAtEpochMs = 3L,
                        activeSegmentStartedAtEpochMs = 1L,
                        fileBytes = 4L,
                        checkpointPath = null,
                        state = SessionJournalState.STOPPING,
                        correlationId = "corr",
                    ),
                )
            // A hung binder call: the future never completes, so the bounded query
            // times out instead of stalling startup behind the ownership lock.
            RecordingFinalizeWorkRequest.workQueryTimeoutMsOverrideForTest = 50L
            try {
                every {
                    workManager.getWorkInfosByTag(RecordingFinalizeWorkRequest.workTag(recordingId))
                } returns SettableFuture.create()

                val enqueuedSessionIds = reconciler.reconcilePendingFinalizations()

                // Unknown finalize state fails closed: the session is reported as handled
                // (excluded from synchronous recovery) and no finalize work is enqueued.
                assertEquals(setOf(sessionId), enqueuedSessionIds)
                verify(exactly = 0) {
                    workManager.beginUniqueWork(
                        RecordingFinalizeWorkRequest.FINALIZE_PIPELINE,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        any<OneTimeWorkRequest>(),
                    )
                }
            } finally {
                RecordingFinalizeWorkRequest.workQueryTimeoutMsOverrideForTest = null
            }
        }

    @Test
    fun `reconcilePendingFinalizations skips row already finalized by a completed worker`() =
        runTest {
            val sessionId = UUID.randomUUID()
            val recordingId = UUID.randomUUID()
            every { journal.loadAllEntries() } returns
                listOf(
                    RecordingSessionEntry(
                        sessionId = sessionId,
                        audioPath = "/tmp/active.m4a",
                        finalAudioPath = "/tmp/final.m4a",
                        segmentPaths = listOf("/tmp/active.m4a"),
                        origin = RecordingOrigin.APP,
                        profileId = null,
                        recordingId = recordingId,
                        startedAtEpochMs = 1L,
                        lastHeartbeatEpochMs = 2L,
                        lastSegmentFinalizedAtEpochMs = 3L,
                        activeSegmentStartedAtEpochMs = 1L,
                        fileBytes = 4L,
                        checkpointPath = null,
                        state = SessionJournalState.STOPPING,
                        correlationId = "corr",
                    ),
                )
            // The worker finished: no unfinished work, and the row left RECORDING.
            every {
                workManager.getWorkInfosByTag(RecordingFinalizeWorkRequest.workTag(recordingId))
            } returns Futures.immediateFuture(emptyList())
            coEvery { recordingRepository.getRecording(recordingId) } returns
                Recording(
                    id = recordingId,
                    title = "Saved",
                    audioPath = "/tmp/final.m4a",
                    source = RecordingSource.APP,
                    status = RecordingStatus.PENDING_TRANSCRIPTION,
                    createdAt = Date(),
                )

            val enqueuedSessionIds = reconciler.reconcilePendingFinalizations()

            assertEquals(emptySet<UUID>(), enqueuedSessionIds)
            verify(exactly = 0) {
                workManager.beginUniqueWork(
                    RecordingFinalizeWorkRequest.FINALIZE_PIPELINE,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    any<OneTimeWorkRequest>(),
                )
            }
        }
}
