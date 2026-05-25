package dev.chirpboard.app.feature.transcription

import dev.chirpboard.app.data.model.RecordingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionOutcomeMappingTest {

    // --- Exception hierarchy contract tests ---
    // shouldRetry() in TranscriptionWorker checks `is java.io.IOException`.
    // These tests document and protect that contract so refactors cannot
    // silently break the retry path.

    @Test
    fun `RetryableTranscriptionException is IOException so retry policy fires`() {
        val exception: Exception = RetryableTranscriptionException("transient codec issue")

        assertTrue(exception is java.io.IOException)
    }

    @Test
    fun `NonRetryableTranscriptionException is not IOException so retry policy skips`() {
        val exception: Exception = NonRetryableTranscriptionException("model unavailable")

        assertFalse(exception is java.io.IOException)
    }

    @Test
    fun `retryable worker failure stays pending and retries`() {
        val disposition = resolveWorkerFailureDisposition(
            exception = RetryableTranscriptionException("temporary decoder issue"),
            runAttemptCount = 0,
            maxRetryCount = 3
        )

        assertEquals(RecordingStatus.PENDING_TRANSCRIPTION, disposition.status)
        assertTrue(disposition.retry)
    }

    @Test
    fun `terminal worker failure is marked failed`() {
        val disposition = resolveWorkerFailureDisposition(
            exception = NonRetryableTranscriptionException("bad audio format"),
            runAttemptCount = 0,
            maxRetryCount = 3
        )

        assertEquals(RecordingStatus.FAILED, disposition.status)
        assertFalse(disposition.retry)
    }

    @Test
    fun `retryable worker failure becomes terminal after max retries`() {
        val disposition = resolveWorkerFailureDisposition(
            exception = RetryableTranscriptionException("temporary decoder issue"),
            runAttemptCount = 3,
            maxRetryCount = 3
        )

        assertEquals(RecordingStatus.FAILED, disposition.status)
        assertFalse(disposition.retry)
    }
}
