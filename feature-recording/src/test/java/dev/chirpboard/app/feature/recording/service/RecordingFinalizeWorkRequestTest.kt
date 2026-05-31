package dev.chirpboard.app.feature.recording.service

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkContinuation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.Futures
import dev.chirpboard.app.core.recording.RecordingOrigin
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class RecordingFinalizeWorkRequestTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var continuation: WorkContinuation

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        workManager = mockk(relaxed = true)
        continuation = mockk(relaxed = true)

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(context) } returns workManager
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `enqueue replaces failed finalize chains and tags recording work`() {
        val recordingId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val requestSlot = slot<OneTimeWorkRequest>()
        every {
            workManager.beginUniqueWork(
                RecordingFinalizeWorkRequest.FINALIZE_PIPELINE,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                capture(requestSlot),
            )
        } returns continuation

        RecordingFinalizeWorkRequest.enqueue(
            context = context,
            snapshot =
                StopSnapshot(
                    origin = RecordingOrigin.APP,
                    profileId = null,
                    recordingId = recordingId,
                    audioFilePath = "/tmp/recording.m4a",
                    durationMs = 1_000L,
                    stoppedAtEpochMs = 2_000L,
                    wasPaused = false,
                    correlationId = "record-test",
                ),
            sessionId = sessionId,
        )

        verify { continuation.enqueue() }
        val request = requestSlot.captured
        assertTrue(request.tags.contains(RecordingFinalizeWorkRequest.WORK_TAG_FINALIZE))
        assertTrue(request.tags.contains(RecordingFinalizeWorkRequest.workTag(recordingId)))
        assertEquals(
            recordingId.toString(),
            request.workSpec.input.getString(RecordingFinalizeWorkKeys.INPUT_RECORDING_ID),
        )
        assertEquals(
            sessionId.toString(),
            request.workSpec.input.getString(RecordingFinalizeWorkKeys.INPUT_SESSION_ID),
        )
    }

    @Test
    fun `hasUnfinishedWork returns true for active recording tag work`() =
        runTest {
            val recordingId = UUID.randomUUID()
            val workInfo =
                mockk<WorkInfo> {
                    every { state } returns WorkInfo.State.RUNNING
                }
            every {
                workManager.getWorkInfosByTag(RecordingFinalizeWorkRequest.workTag(recordingId))
            } returns Futures.immediateFuture(listOf(workInfo))

            assertTrue(RecordingFinalizeWorkRequest.hasUnfinishedWork(context, recordingId))
        }

    @Test
    fun `hasUnfinishedWork returns false when tagged work is finished`() =
        runTest {
            val recordingId = UUID.randomUUID()
            val workInfo =
                mockk<WorkInfo> {
                    every { state } returns WorkInfo.State.FAILED
                }
            every {
                workManager.getWorkInfosByTag(RecordingFinalizeWorkRequest.workTag(recordingId))
            } returns Futures.immediateFuture(listOf(workInfo))

            assertFalse(RecordingFinalizeWorkRequest.hasUnfinishedWork(context, recordingId))
        }
}
