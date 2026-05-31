package dev.chirpboard.app.feature.recording.session

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkContinuation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.Futures
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.service.RecordingFinalizeWorkRequest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Date
import java.util.UUID

class RecordingFinalizeStartupReconcilerTest {
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
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(context) } returns workManager
        every {
            workManager.beginUniqueWork(any(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>())
        } returns mockk<WorkContinuation>(relaxed = true)
        reconciler =
            RecordingFinalizeStartupReconciler(
                context = context,
                sessionJournal = journal,
                recordingRepository = recordingRepository,
            )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `reconcilePendingFinalizations skips recording with unfinished finalize work`() =
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

            reconciler.reconcilePendingFinalizations()

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

            reconciler.reconcilePendingFinalizations()

            verify {
                workManager.beginUniqueWork(
                    RecordingFinalizeWorkRequest.FINALIZE_PIPELINE,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    any<OneTimeWorkRequest>(),
                )
            }
        }
}
