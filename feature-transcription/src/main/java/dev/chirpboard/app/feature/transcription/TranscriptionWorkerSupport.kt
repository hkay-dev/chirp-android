package dev.chirpboard.app.feature.transcription

import androidx.work.Data
import dev.chirpboard.app.core.transcription.RecognizedWordTiming
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.data.model.RecordingStatus
import java.util.UUID

internal const val TRANSCRIPTION_MAX_RETRY_COUNT = 3

internal fun buildTranscriptionFailureResult(errorMessage: String): androidx.work.ListenableWorker.Result {
    return androidx.work.ListenableWorker.Result.failure(
        Data.Builder()
            .putString(TranscriptionWorker.OUTPUT_ERROR, errorMessage)
            .build()
    )
}

internal fun buildTranscriptionSuccessResult(transcriptId: UUID): androidx.work.ListenableWorker.Result {
    return androidx.work.ListenableWorker.Result.success(
        Data.Builder()
            .putString(TranscriptionWorker.OUTPUT_TRANSCRIPT_ID, transcriptId.toString())
            .build()
    )
}

internal data class ChunkTranscription(
    val text: String,
    val wordTimings: List<RecognizedWordTiming>? = null,
 )

internal fun mapOutcomeForChunkTranscription(outcome: TranscriptionOutcome): ChunkTranscription {
    return when (outcome) {
        is TranscriptionOutcome.Success -> ChunkTranscription(
            text = outcome.text,
            wordTimings = outcome.wordTimings,
        )
        TranscriptionOutcome.NoSpeech -> ChunkTranscription(text = "")
        is TranscriptionOutcome.ModelUnavailable -> {
            throw NonRetryableTranscriptionException(
                "Speech model unavailable: ${outcome.reason}"
            )
        }
        is TranscriptionOutcome.EngineError -> {
            val message = "Speech engine failed: ${outcome.reason}"
            if (outcome.retryable) {
                throw RetryableTranscriptionException(message)
            } else {
                throw NonRetryableTranscriptionException(message)
            }
        }
    }
}

internal data class WorkerFailureDisposition(
    val status: RecordingStatus,
    val retry: Boolean
)

internal fun resolveWorkerFailureDisposition(
    exception: Exception,
    runAttemptCount: Int,
    maxRetryCount: Int
): WorkerFailureDisposition {
    val retry = exception is java.io.IOException && runAttemptCount < maxRetryCount
    val status = if (retry) RecordingStatus.PENDING_TRANSCRIPTION else RecordingStatus.FAILED
    return WorkerFailureDisposition(status = status, retry = retry)
}

internal class RetryableTranscriptionException(message: String) : java.io.IOException(message)
internal class NonRetryableTranscriptionException(message: String) : Exception(message)
