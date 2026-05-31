package dev.chirpboard.app.feature.transcription

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class TranscriptionWorkRequestTest {
    private val recordingId = UUID.randomUUID()

    @Test
    fun `workName generates correct string`() {
        assertEquals("transcription_$recordingId", TranscriptionWorkRequest.workName(recordingId))
    }

    @Test
    fun `build creates work request with production inputs tags and constraints`() {
        val request =
            TranscriptionWorkRequest.build(
                recordingId = recordingId,
                executionToken = "transcription-token",
                correlationId = "test-correlation",
            )

        val inputData = request.workSpec.input
        assertEquals(recordingId.toString(), inputData.getString(TranscriptionWorker.INPUT_RECORDING_ID))
        assertEquals("test-correlation", inputData.getString(TranscriptionWorkRequest.INPUT_CORRELATION_ID))
        assertEquals("transcription-token", inputData.getString(TranscriptionWorkRequest.INPUT_EXECUTION_TOKEN))

        assertTrue(request.tags.contains(TranscriptionWorkRequest.WORK_TAG_TRANSCRIPTION))
        assertTrue(request.tags.contains("recording_$recordingId"))
        assertTrue(request.workSpec.constraints.requiresBatteryNotLow())
        assertTrue(request.workSpec.constraints.requiresStorageNotLow())
        assertEquals(NetworkType.NOT_REQUIRED, request.workSpec.constraints.requiredNetworkType)
    }
}
