package dev.chirpboard.app.feature.transcription

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID

/**
 * Helper object for creating and enqueuing saved-recording LLM enhancement work.
 */
object RecordingEnhancementWorkRequest {
    const val WORK_TAG_ENHANCEMENT = "recording_enhancement"
    const val INPUT_RECORDING_ID = "recording_id"
    const val INPUT_CORRELATION_ID = "correlation_id"
    const val INPUT_EXECUTION_TOKEN = "execution_token"
    private const val WORK_NAME_PREFIX = "enhancement_"

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

        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        return OneTimeWorkRequestBuilder<RecordingEnhancementWorker>()
            .setInputData(inputDataBuilder.build())
            .setConstraints(constraints)
            .addTag(WORK_TAG_ENHANCEMENT)
            .addTag("${TranscriptionWorkRequest.WORK_TAG_RECORDING_PREFIX}$recordingId")
            .build()
    }

    fun enqueue(
        context: Context,
        recordingId: UUID,
        correlationId: String? = null,
        executionToken: String = UUID.randomUUID().toString(),
    ): String {
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                workName(recordingId),
                ExistingWorkPolicy.KEEP,
                build(recordingId, executionToken, correlationId),
            )

        return workName(recordingId)
    }

    fun cancel(
        context: Context,
        recordingId: UUID,
    ) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(recordingId))
    }
}
