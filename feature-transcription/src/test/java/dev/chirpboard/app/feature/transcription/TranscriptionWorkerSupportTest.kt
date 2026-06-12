package dev.chirpboard.app.feature.transcription

import android.content.pm.ServiceInfo
import androidx.work.Data
import androidx.work.ListenableWorker
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.data.model.RecordingStatus
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptionWorkerSupportTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

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

    // I18N-08: notification titles moved to string resources; the helpers now resolve through
    // Context, so these tests pin the stable ids and the resource lookup rather than literals.
    @Test
    fun `transcription foreground helpers use stable ids`() {
        val context = mockk<android.content.Context>()
        every { context.getString(R.string.transcription_progress_notification_title) } returns "Transcribing recording"
        assertEquals("transcription_progress", TRANSCRIPTION_FOREGROUND_CHANNEL_ID)
        assertEquals(2001, TRANSCRIPTION_FOREGROUND_NOTIFICATION_ID)
        assertEquals("Transcribing recording", transcriptionProgressNotificationTitle(context))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, backgroundWorkerForegroundServiceType())
    }

    @Test
    fun `enhancement foreground helpers use stable typed ids`() {
        val context = mockk<android.content.Context>()
        every { context.getString(R.string.enhancement_progress_notification_title) } returns "Applying AI processing"
        assertEquals("enhancement_progress", ENHANCEMENT_FOREGROUND_CHANNEL_ID)
        assertEquals(2002, ENHANCEMENT_FOREGROUND_NOTIFICATION_ID)
        assertEquals("Applying AI processing", enhancementProgressNotificationTitle(context))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, backgroundWorkerForegroundServiceType())
    }

    @Test
    fun `transcriptionFailureNotificationText maps failure classes to actionable copy`() {
        val context = mockk<android.content.Context>()
        every { context.getString(R.string.transcription_error_model_missing) } returns "model-missing"
        every { context.getString(R.string.transcription_error_storage_full) } returns "storage-full"
        every { context.getString(R.string.transcription_error_generic) } returns "generic"

        assertEquals(
            "model-missing",
            transcriptionFailureNotificationText(
                context,
                NonRetryableTranscriptionException("Speech model unavailable: missing files"),
            ),
        )
        assertEquals(
            "storage-full",
            transcriptionFailureNotificationText(
                context,
                java.io.IOException("write failed: ENOSPC (No space left on device)"),
            ),
        )
        assertEquals(
            "generic",
            transcriptionFailureNotificationText(context, IllegalStateException("boom")),
        )
    }

    @Test
    fun `computeActiveWaitTimeoutMs scales with duration and clamps to max`() {
        assertEquals(TRANSCRIPTION_MIN_ACTIVE_WAIT_MS, computeActiveWaitTimeoutMs(0))
        assertEquals(TRANSCRIPTION_MIN_ACTIVE_WAIT_MS + 120_000L, computeActiveWaitTimeoutMs(120_000L))
        assertEquals(TRANSCRIPTION_MAX_ACTIVE_WAIT_MS, computeActiveWaitTimeoutMs(10 * 60 * 60 * 1000L))
    }

    @Test
    fun `awaitRecordingInactive completes when recording becomes idle`() = runTest {
        val manager = RecordingStateManager()
        manager.tryStartRecording(RecordingOrigin.APP, null)
        manager.onRecordingStarted(audioFilePath = "path/to/file")
        val waitJob =
            async {
                awaitRecordingInactive(manager.state, timeoutMs = 5_000L)
            }

        manager.transitionToStopping()
        manager.onRecordingCompleted()

        waitJob.await()
    }

    @Test
    fun `awaitRecordingInactive fails when active state persists beyond timeout`() = runTest {
        val manager = RecordingStateManager()
        manager.tryStartRecording(RecordingOrigin.APP, null)

        var caught: Throwable? = null
        val waitJob =
            launch {
                try {
                    awaitRecordingInactive(manager.state, timeoutMs = 1_000L)
                } catch (error: Throwable) {
                    caught = error
                }
            }

        advanceTimeBy(1_001L)
        waitJob.join()

        assertTrue(caught is ActiveRecordingWaitTimeoutException)
    }

    @Test
    fun `sanitizeGeneratedTitle strips wrapping quotes and collapses newlines`() {
        assertEquals("Weekly Sync Notes", sanitizeGeneratedTitle("\"Weekly Sync\nNotes\""))
        assertEquals("Budget review", sanitizeGeneratedTitle("`Budget review`"))
        assertEquals("Plan for launch", sanitizeGeneratedTitle("- Plan for launch"))
        assertEquals("Quarterly recap", sanitizeGeneratedTitle("  Quarterly   recap  "))
    }

    @Test
    fun `sanitizeGeneratedTitle caps length and can yield empty for unusable output`() {
        val longTitle = "word ".repeat(40).trim()
        assertTrue(sanitizeGeneratedTitle(longTitle).length <= GENERATED_TITLE_MAX_LENGTH)
        assertEquals("", sanitizeGeneratedTitle("\"\""))
        assertEquals("", sanitizeGeneratedTitle("   "))
    }

    @Test
    fun `sanitizeGeneratedSummary trims wrapping and caps length`() {
        assertEquals("A short summary.", sanitizeGeneratedSummary("  \"A short summary.\"  "))
        val longSummary = "s".repeat(GENERATED_SUMMARY_MAX_LENGTH + 100)
        assertEquals(GENERATED_SUMMARY_MAX_LENGTH, sanitizeGeneratedSummary(longSummary).length)
        // Multi-sentence summaries keep their internal newlines; only the edges normalize.
        assertEquals("Line one.\nLine two.", sanitizeGeneratedSummary("Line one.\nLine two.\n"))
    }
}
