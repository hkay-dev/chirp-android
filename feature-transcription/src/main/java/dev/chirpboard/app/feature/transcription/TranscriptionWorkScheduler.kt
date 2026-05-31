package dev.chirpboard.app.feature.transcription

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class ScheduledWorkState {
    ENQUEUED,
    RUNNING,
    BLOCKED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class ScheduledWorkInfo(
    val state: ScheduledWorkState,
)

interface TranscriptionWorkScheduler {
    fun enqueueTranscription(
        recordingId: UUID,
        executionToken: String,
        correlationId: String? = null,
    ): String

    fun enqueueEnhancement(
        recordingId: UUID,
        executionToken: String,
        correlationId: String? = null,
    ): String

    fun cancelTranscription(recordingId: UUID)

    fun cancelEnhancement(recordingId: UUID)

    suspend fun getWorkInfosByRecordingTag(recordingId: UUID): List<ScheduledWorkInfo>?

    suspend fun getWorkInfosForUniqueWork(workName: String): List<ScheduledWorkInfo>?
}

@Singleton
internal class WorkManagerTranscriptionWorkScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : TranscriptionWorkScheduler {
        private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

        override fun enqueueTranscription(
            recordingId: UUID,
            executionToken: String,
            correlationId: String?,
        ): String {
            workManager.enqueueUniqueWork(
                TranscriptionWorkRequest.workName(recordingId),
                androidx.work.ExistingWorkPolicy.KEEP,
                TranscriptionWorkRequest.build(recordingId, executionToken, correlationId),
            )
            return TranscriptionWorkRequest.workName(recordingId)
        }

        override fun enqueueEnhancement(
            recordingId: UUID,
            executionToken: String,
            correlationId: String?,
        ): String {
            workManager.enqueueUniqueWork(
                RecordingEnhancementWorkRequest.workName(recordingId),
                androidx.work.ExistingWorkPolicy.KEEP,
                RecordingEnhancementWorkRequest.build(recordingId, executionToken, correlationId),
            )
            return RecordingEnhancementWorkRequest.workName(recordingId)
        }

        override fun cancelTranscription(recordingId: UUID) {
            workManager.cancelUniqueWork(TranscriptionWorkRequest.workName(recordingId))
        }

        override fun cancelEnhancement(recordingId: UUID) {
            workManager.cancelUniqueWork(RecordingEnhancementWorkRequest.workName(recordingId))
        }

        override suspend fun getWorkInfosByRecordingTag(recordingId: UUID): List<ScheduledWorkInfo>? =
            loadWorkInfosWithTimeout(
                workManager.getWorkInfosByTag("${TranscriptionWorkRequest.WORK_TAG_RECORDING_PREFIX}$recordingId"),
            )

        override suspend fun getWorkInfosForUniqueWork(workName: String): List<ScheduledWorkInfo>? =
            loadWorkInfosWithTimeout(workManager.getWorkInfosForUniqueWork(workName))

        private suspend fun loadWorkInfosWithTimeout(future: ListenableFuture<List<WorkInfo>>): List<ScheduledWorkInfo>? =
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(WORK_INFO_TIMEOUT_MS) {
                    while (!future.isDone && !future.isCancelled) {
                        delay(WORK_INFO_POLL_INTERVAL_MS)
                    }
                    if (future.isCancelled) {
                        emptyList()
                    } else {
                        future.get().map { ScheduledWorkInfo(it.state.toScheduledWorkState()) }
                    }
                }
            }

        private fun WorkInfo.State.toScheduledWorkState(): ScheduledWorkState =
            when (this) {
                WorkInfo.State.ENQUEUED -> ScheduledWorkState.ENQUEUED
                WorkInfo.State.RUNNING -> ScheduledWorkState.RUNNING
                WorkInfo.State.BLOCKED -> ScheduledWorkState.BLOCKED
                WorkInfo.State.SUCCEEDED -> ScheduledWorkState.SUCCEEDED
                WorkInfo.State.FAILED -> ScheduledWorkState.FAILED
                WorkInfo.State.CANCELLED -> ScheduledWorkState.CANCELLED
            }

        private companion object {
            private const val WORK_INFO_TIMEOUT_MS = 5_000L
            private const val WORK_INFO_POLL_INTERVAL_MS = 50L
        }
    }
