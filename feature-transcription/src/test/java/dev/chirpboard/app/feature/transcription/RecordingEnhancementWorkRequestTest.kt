package dev.chirpboard.app.feature.transcription

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class RecordingEnhancementWorkRequestTest {
    private val recordingId = UUID.randomUUID()

    @Test
    fun `workName generates enhancement-specific name`() {
        assertEquals("enhancement_$recordingId", RecordingEnhancementWorkRequest.workName(recordingId))
    }

    @Test
    fun `build creates network constrained enhancement work`() {
        val request =
            RecordingEnhancementWorkRequest.build(
                recordingId = recordingId,
                executionToken = "enhancement-token",
                correlationId = "corr",
            )

        assertEquals(recordingId.toString(), request.workSpec.input.getString(RecordingEnhancementWorkRequest.INPUT_RECORDING_ID))
        assertEquals("corr", request.workSpec.input.getString(RecordingEnhancementWorkRequest.INPUT_CORRELATION_ID))
        assertEquals("enhancement-token", request.workSpec.input.getString(RecordingEnhancementWorkRequest.INPUT_EXECUTION_TOKEN))
        assertTrue(request.tags.contains(RecordingEnhancementWorkRequest.WORK_TAG_ENHANCEMENT))
        assertTrue(request.tags.contains("${TranscriptionWorkRequest.WORK_TAG_RECORDING_PREFIX}$recordingId"))
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }
}
