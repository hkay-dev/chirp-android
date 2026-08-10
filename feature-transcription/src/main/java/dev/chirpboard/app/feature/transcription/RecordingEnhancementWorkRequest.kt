package dev.chirpboard.app.feature.transcription

import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Helper object for creating and enqueuing saved-recording LLM enhancement work.
 */
object RecordingEnhancementWorkRequest {
    const val WORK_TAG_ENHANCEMENT = "recording_enhancement"
    const val INPUT_RECORDING_ID = "recording_id"
    const val INPUT_CORRELATION_ID = "correlation_id"
    const val INPUT_EXECUTION_TOKEN = "execution_token"
    private const val WORK_NAME_PREFIX = "enhancement_"
    private const val RETRY_BACKOFF_SECONDS = 30L

    fun workName(recordingId: UUID): String = "$WORK_NAME_PREFIX$recordingId"

    fun build(
        recordingId: UUID,
        executionToken: String,
        correlationId: String? = null,
    ): OneTimeWorkRequest {
        val inputDataBuilder =
            Data
                .Builder()
                .putString(INPUT_RECORDING_ID, recordingId.toString())
                .putString(INPUT_EXECUTION_TOKEN, executionToken)

        if (correlationId != null) {
            inputDataBuilder.putString(INPUT_CORRELATION_ID, correlationId)
        }

        return OneTimeWorkRequestBuilder<RecordingEnhancementWorker>()
            .setInputData(inputDataBuilder.build())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                RETRY_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .addTag(WORK_TAG_ENHANCEMENT)
            .addTag("${TranscriptionWorkRequest.WORK_TAG_RECORDING_PREFIX}$recordingId")
            .build()
    }
}
