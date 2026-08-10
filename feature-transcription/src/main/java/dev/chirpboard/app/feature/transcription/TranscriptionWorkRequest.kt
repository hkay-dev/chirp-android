package dev.chirpboard.app.feature.transcription

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import java.util.UUID

/**
 * Helper object for creating and enqueuing transcription work requests.
 */
object TranscriptionWorkRequest {

    const val WORK_TAG_TRANSCRIPTION = "transcription"
    const val WORK_TAG_RECORDING_PREFIX = "recording_"
    const val INPUT_CORRELATION_ID = "correlation_id"
    const val INPUT_EXECUTION_TOKEN = "execution_token"
    private const val WORK_NAME_PREFIX = "transcription_"

    /**
     * Gets the unique work name for a recording's transcription.
     */
    fun workName(recordingId: UUID): String = "$WORK_NAME_PREFIX${recordingId}"

    fun build(
        recordingId: UUID,
        executionToken: String,
        correlationId: String? = null,
        requiresNetwork: Boolean = false,
    ): OneTimeWorkRequest {
        val inputDataBuilder = Data.Builder()
            .putString(TranscriptionWorker.INPUT_RECORDING_ID, recordingId.toString())
            .putString(INPUT_EXECUTION_TOKEN, executionToken)

        if (correlationId != null) {
            inputDataBuilder.putString(INPUT_CORRELATION_ID, correlationId)
        }

        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .setRequiredNetworkType(
                if (requiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED,
            )
            .build()

        return OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(inputDataBuilder.build())
            .setConstraints(constraints)
            .addTag(WORK_TAG_TRANSCRIPTION)
            .addTag("$WORK_TAG_RECORDING_PREFIX$recordingId")
            .build()
    }
}
