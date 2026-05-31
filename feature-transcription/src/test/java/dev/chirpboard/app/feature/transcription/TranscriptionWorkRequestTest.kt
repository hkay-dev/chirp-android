package dev.chirpboard.app.feature.transcription

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkInfo
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

class TranscriptionWorkRequestTest {

    private lateinit var mockContext: Context
    private lateinit var mockWorkManager: WorkManager
    private val testRecordingId = UUID.randomUUID()

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockWorkManager = mockk(relaxed = true)

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(mockContext) } returns mockWorkManager
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `workName generates correct string`() {
        val name = TranscriptionWorkRequest.workName(testRecordingId)
        assertEquals("transcription_$testRecordingId", name)
    }

    @Test
    fun `enqueue creates work request with correct parameters and enqueues it`() {
        val workRequestSlot = slot<OneTimeWorkRequest>()
        val mockOperation = mockk<Operation>()
        
        every {
            mockWorkManager.enqueueUniqueWork(
                any(),
                any<ExistingWorkPolicy>(),
                capture(workRequestSlot)
            )
        } returns mockOperation

        val resultId = TranscriptionWorkRequest.enqueue(mockContext, testRecordingId, "test-correlation")

        verify {
            mockWorkManager.enqueueUniqueWork(
                "transcription_$testRecordingId",
                ExistingWorkPolicy.KEEP,
                any<OneTimeWorkRequest>()
            )
        }

        val capturedRequest = workRequestSlot.captured
        assertEquals(resultId, "transcription_$testRecordingId")
        
        val inputData = capturedRequest.workSpec.input
        assertEquals(testRecordingId.toString(), inputData.getString(TranscriptionWorker.INPUT_RECORDING_ID))
        assertEquals("test-correlation", inputData.getString(TranscriptionWorkRequest.INPUT_CORRELATION_ID))
        
        val tags = capturedRequest.tags
        assertTrue(tags.contains(TranscriptionWorkRequest.WORK_TAG_TRANSCRIPTION))
        assertTrue(tags.contains("recording_$testRecordingId"))
        
        assertTrue(capturedRequest.workSpec.constraints.requiresBatteryNotLow())
        assertTrue(capturedRequest.workSpec.constraints.requiresStorageNotLow())
    }

    @Test
    fun `cancel calls cancelUniqueWork with correct name`() {
        TranscriptionWorkRequest.cancel(mockContext, testRecordingId)
        
        verify {
            mockWorkManager.cancelUniqueWork("transcription_$testRecordingId")
        }
    }

    @Test
    fun `cancelAll calls cancelAllWorkByTag`() {
        TranscriptionWorkRequest.cancelAll(mockContext)
        
        verify {
            mockWorkManager.cancelAllWorkByTag(TranscriptionWorkRequest.WORK_TAG_TRANSCRIPTION)
        }
    }

    @Test
    fun `getWorkInfo returns LiveData from WorkManager`() {
        val mockLiveData = mockk<LiveData<List<WorkInfo>>>()
        every {
            mockWorkManager.getWorkInfosByTagLiveData("recording_$testRecordingId")
        } returns mockLiveData

        val result = TranscriptionWorkRequest.getWorkInfo(mockContext, testRecordingId)
        
        assertEquals(mockLiveData, result)
        verify {
            mockWorkManager.getWorkInfosByTagLiveData("recording_$testRecordingId")
        }
    }
}
