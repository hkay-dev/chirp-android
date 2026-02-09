package dev.chirpboard.app.feature.transcription

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID

/**
 * Helper object for creating and enqueuing transcription work requests.
 */
object TranscriptionWorkRequest {

    const val WORK_TAG_TRANSCRIPTION = "transcription"
    const val INPUT_CORRELATION_ID = "correlation_id"
    private const val WORK_NAME_PREFIX = "transcription_"
    private const val WORK_TAG_RECORDING_PREFIX = "recording_"

    /**
     * Gets the unique work name for a recording's transcription.
     *
     * @param recordingId UUID of the recording
     * @return Unique work name
     */
    fun workName(recordingId: UUID): String = "$WORK_NAME_PREFIX${recordingId}"

    /**
     * Creates and enqueues a transcription work request for the given recording.
     *
     * Uses [ExistingWorkPolicy.KEEP] to prevent duplicate work for the same recording.
     * If transcription is already in progress for this recording, the existing work continues.
     *
     * @param context Application context
     * @param recordingId UUID of the recording to transcribe
     * @return The work request UUID for tracking
     */
    fun enqueue(
        context: Context,
        recordingId: UUID,
        correlationId: String? = null
    ): UUID {
        val inputDataBuilder = Data.Builder()
            .putString(TranscriptionWorker.INPUT_RECORDING_ID, recordingId.toString())

        if (correlationId != null) {
            inputDataBuilder.putString(INPUT_CORRELATION_ID, correlationId)
        }

        val inputData = inputDataBuilder.build()

        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .addTag(WORK_TAG_TRANSCRIPTION)
            .addTag("$WORK_TAG_RECORDING_PREFIX$recordingId")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                workName(recordingId),
                ExistingWorkPolicy.KEEP,
                workRequest
            )

        return workRequest.id
    }

    /**
     * Cancels any pending transcription work for the given recording.
     *
     * @param context Application context
     * @param recordingId UUID of the recording
     */
    fun cancel(context: Context, recordingId: UUID) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(recordingId))
    }

    /**
     * Cancels all pending transcription work.
     *
     * @param context Application context
     */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG_TRANSCRIPTION)
    }

    /**
     * Gets the work info for a specific recording's transcription.
     *
     * @param context Application context
     * @param recordingId UUID of the recording
     * @return LiveData of work info list
     */
    fun getWorkInfo(context: Context, recordingId: UUID) =
        WorkManager.getInstance(context)
            .getWorkInfosByTagLiveData("$WORK_TAG_RECORDING_PREFIX$recordingId")
}
