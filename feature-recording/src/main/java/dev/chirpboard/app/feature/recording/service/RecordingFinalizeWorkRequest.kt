package dev.chirpboard.app.feature.recording.service

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

object RecordingFinalizeWorkRequest {
    const val FINALIZE_PIPELINE = "recording_finalize_pipeline"
    const val WORK_TAG_FINALIZE = "recording_finalize"
    private const val WORK_TAG_RECORDING_PREFIX = "recording_finalize_"
    private const val WORK_QUERY_TIMEOUT_MS = 5_000L

    /** Test-only override for the bounded work-query timeout. */
    @VisibleForTesting
    internal var workQueryTimeoutMsOverrideForTest: Long? = null

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

    /**
     * Bounded query: callers hold the finalize ownership lock while asking, so an
     * unbounded [java.util.concurrent.Future.get] on a hung WorkManager binder call
     * would stall user recovery actions behind the mutex indefinitely. A timeout
     * throws like any other query failure and callers fail closed for that pass.
     */
    suspend fun hasUnfinishedWork(
        context: Context,
        recordingId: UUID,
    ): Boolean =
        withContext(Dispatchers.IO) {
            WorkManager.getInstance(context)
                .getWorkInfosByTag(workTag(recordingId))
                .get(workQueryTimeoutMsOverrideForTest ?: WORK_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .any { workInfo -> !workInfo.state.isFinished }
        }
}
