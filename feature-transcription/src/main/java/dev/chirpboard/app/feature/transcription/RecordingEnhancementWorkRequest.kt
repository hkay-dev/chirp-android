package dev.chirpboard.app.feature.transcription

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
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
    private const val WORK_NAME_PREFIX = "enhancement_"

    fun workName(recordingId: UUID): String = "$WORK_NAME_PREFIX$recordingId"

    fun enqueue(
        context: Context,
        recordingId: UUID,
        correlationId: String? = null,
    ): String {
        val inputDataBuilder =
            Data
                .Builder()
                .putString(INPUT_RECORDING_ID, recordingId.toString())

        if (correlationId != null) {
            inputDataBuilder.putString(INPUT_CORRELATION_ID, correlationId)
        }

        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        val workRequest =
            OneTimeWorkRequestBuilder<RecordingEnhancementWorker>()
                .setInputData(inputDataBuilder.build())
                .setConstraints(constraints)
                .addTag(WORK_TAG_ENHANCEMENT)
                .addTag("${TranscriptionWorkRequest.WORK_TAG_RECORDING_PREFIX}$recordingId")
                .build()

        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                workName(recordingId),
                ExistingWorkPolicy.KEEP,
                workRequest,
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
