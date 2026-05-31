package dev.chirpboard.app.feature.recording.service

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

object RecordingFinalizeWorkRequest {
    const val FINALIZE_PIPELINE = "recording_finalize_pipeline"
    const val WORK_TAG_FINALIZE = "recording_finalize"
    private const val WORK_TAG_RECORDING_PREFIX = "recording_finalize_"

    fun workTag(recordingId: UUID): String = "$WORK_TAG_RECORDING_PREFIX$recordingId"

    fun enqueue(
        context: Context,
        snapshot: StopSnapshot,
        sessionId: UUID?,
    ): String {
        val recordingId =
            snapshot.recordingId
                ?: error("Finalize enqueue requires recordingId")

        val request =
            OneTimeWorkRequestBuilder<RecordingFinalizeWorker>()
                .setInputData(snapshot.toWorkData(sessionId))
                .addTag(WORK_TAG_FINALIZE)
                .addTag(workTag(recordingId))
                .build()

        WorkManager.getInstance(context)
            .beginUniqueWork(
                FINALIZE_PIPELINE,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            ).enqueue()

        return request.id.toString()
    }

    suspend fun hasUnfinishedWork(
        context: Context,
        recordingId: UUID,
    ): Boolean =
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(context)
                .getWorkInfosByTag(workTag(recordingId))
                .get()
                .any { workInfo -> !workInfo.state.isFinished }
        }
}
