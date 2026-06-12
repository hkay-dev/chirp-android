package dev.chirpboard.app.feature.transcription

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ExecutionException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
                // Suspend on the future's completion listener instead of busy-polling every
                // 50ms. withTimeoutOrNull still bounds the wait and returns null on timeout
                // (the reconciler's "fail closed" signal); a future that completes
                // exceptionally surfaces through await() and is handled by the callers'
                // catch blocks rather than being silently dropped.
                withTimeoutOrNull(WORK_INFO_TIMEOUT_MS) {
                    val workInfos =
                        try {
                            future.await()
                        } catch (e: java.util.concurrent.CancellationException) {
                            emptyList()
                        }
                    workInfos.map { ScheduledWorkInfo(it.state.toScheduledWorkState()) }
                }
            }

        /**
         * Suspends until this [ListenableFuture] completes, honouring coroutine cancellation
         * by cancelling the future. Equivalent to androidx.concurrent.futures' await(), kept
         * local so the module does not take a new dependency for one call site.
         */
        private suspend fun <T> ListenableFuture<T>.await(): T {
            if (isDone) {
                return getUninterruptibly()
            }
            return suspendCancellableCoroutine { cont ->
                addListener(
                    {
                        try {
                            cont.resume(getUninterruptibly())
                        } catch (e: ExecutionException) {
                            cont.resumeWithException(e.cause ?: e)
                        } catch (e: Throwable) {
                            cont.resumeWithException(e)
                        }
                    },
                    Runnable::run,
                )
                cont.invokeOnCancellation { cancel(false) }
            }
        }

        private fun <T> ListenableFuture<T>.getUninterruptibly(): T =
            try {
                get()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw e
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
        }
    }
