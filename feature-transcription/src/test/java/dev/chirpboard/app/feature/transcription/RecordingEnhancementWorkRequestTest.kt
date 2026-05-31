package dev.chirpboard.app.feature.transcription

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class RecordingEnhancementWorkRequestTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private val recordingId = UUID.randomUUID()

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        workManager = mockk(relaxed = true)
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(context) } returns workManager
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `workName generates enhancement-specific name`() {
        assertEquals("enhancement_$recordingId", RecordingEnhancementWorkRequest.workName(recordingId))
    }

    @Test
    fun `enqueue creates network constrained enhancement work`() {
        val requestSlot = slot<OneTimeWorkRequest>()
        val operation = mockk<Operation>()
        every {
            workManager.enqueueUniqueWork(
                any(),
                any<ExistingWorkPolicy>(),
                capture(requestSlot),
            )
        } returns operation

        val workName = RecordingEnhancementWorkRequest.enqueue(context, recordingId, "corr")

        assertEquals("enhancement_$recordingId", workName)
        verify {
            workManager.enqueueUniqueWork(
                "enhancement_$recordingId",
                ExistingWorkPolicy.KEEP,
                any<OneTimeWorkRequest>(),
            )
        }

        val request = requestSlot.captured
        assertEquals(recordingId.toString(), request.workSpec.input.getString(RecordingEnhancementWorkRequest.INPUT_RECORDING_ID))
        assertEquals("corr", request.workSpec.input.getString(RecordingEnhancementWorkRequest.INPUT_CORRELATION_ID))
        assertTrue(request.tags.contains(RecordingEnhancementWorkRequest.WORK_TAG_ENHANCEMENT))
        assertTrue(request.tags.contains("${TranscriptionWorkRequest.WORK_TAG_RECORDING_PREFIX}$recordingId"))
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `cancel calls cancelUniqueWork with enhancement name`() {
        RecordingEnhancementWorkRequest.cancel(context, recordingId)

        verify {
            workManager.cancelUniqueWork("enhancement_$recordingId")
        }
    }
}
