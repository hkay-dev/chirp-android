package dev.chirpboard.app.feature.transcription

import androidx.work.Data
import androidx.work.ListenableWorker
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.data.model.RecordingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class TranscriptionWorkerSupportTest {

    @Test
    fun `buildTranscriptionFailureResult returns failure with error output`() {
        val result = buildTranscriptionFailureResult("test error")
        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals("test error", result.outputData.getString(TranscriptionWorker.OUTPUT_ERROR))
    }

    @Test
    fun `buildTranscriptionSuccessResult returns success with transcript id`() {
        val uuid = UUID.randomUUID()
        val result = buildTranscriptionSuccessResult(uuid)
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(uuid.toString(), result.outputData.getString(TranscriptionWorker.OUTPUT_TRANSCRIPT_ID))
    }

    @Test
    fun `mapOutcomeForChunkTranscription returns text on Success`() {
        val result = mapOutcomeForChunkTranscription(
            TranscriptionOutcome.Success(text = "hello", wordTimings = emptyList()),
        )
        assertEquals("hello", result.text)
        assertTrue(result.wordTimings?.isEmpty() == true)
    }

    @Test
    fun `mapOutcomeForChunkTranscription returns empty string on NoSpeech`() {
        val result = mapOutcomeForChunkTranscription(TranscriptionOutcome.NoSpeech)
        assertEquals("", result.text)
        assertEquals(null, result.wordTimings)
    }

    @Test(expected = NonRetryableTranscriptionException::class)
    fun `mapOutcomeForChunkTranscription throws NonRetryable on ModelUnavailable`() {
        mapOutcomeForChunkTranscription(TranscriptionOutcome.ModelUnavailable("missing"))
    }

    @Test(expected = RetryableTranscriptionException::class)
    fun `mapOutcomeForChunkTranscription throws Retryable on EngineError if retryable is true`() {
        mapOutcomeForChunkTranscription(TranscriptionOutcome.EngineError("timeout", true))
    }

    @Test(expected = NonRetryableTranscriptionException::class)
    fun `mapOutcomeForChunkTranscription throws NonRetryable on EngineError if retryable is false`() {
        mapOutcomeForChunkTranscription(TranscriptionOutcome.EngineError("crash", false))
    }

    @Test
    fun `resolveWorkerFailureDisposition retries IO exceptions if under max`() {
        val exception = java.io.IOException("network error")
        val result = resolveWorkerFailureDisposition(exception, 1, 3)
        assertTrue(result.retry)
        assertEquals(RecordingStatus.PENDING_TRANSCRIPTION, result.status)
    }

    @Test
    fun `resolveWorkerFailureDisposition does not retry IO exceptions if at max`() {
        val exception = java.io.IOException("network error")
        val result = resolveWorkerFailureDisposition(exception, 3, 3)
        assertFalse(result.retry)
        assertEquals(RecordingStatus.FAILED, result.status)
    }

    @Test
    fun `resolveWorkerFailureDisposition does not retry other exceptions`() {
        val exception = NonRetryableTranscriptionException("bad format")
        val result = resolveWorkerFailureDisposition(exception, 1, 3)
        assertFalse(result.retry)
        assertEquals(RecordingStatus.FAILED, result.status)
    }
}
