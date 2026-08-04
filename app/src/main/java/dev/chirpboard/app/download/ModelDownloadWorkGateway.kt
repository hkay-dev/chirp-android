package dev.chirpboard.app.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.modelreadiness.SpeechModelDownloadGateway
import dev.chirpboard.app.core.modelreadiness.SpeechModelDownloadWork
import dev.chirpboard.app.core.transcription.LocalSpeechModelId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

const val MODEL_DOWNLOAD_WORK_NAME = "model_download"

/**
 * Runs the model download as WorkManager unique work (ERR-1) and mirrors its state for
 * observers. A CONNECTED network constraint keeps a no-network start parked as [Waiting]
 * instead of failing instantly, and exponential backoff spaces the bounded retries (ERR-3).
 */
@Singleton
class ModelDownloadWorkGateway
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SpeechModelDownloadGateway {
        private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        override val work: StateFlow<SpeechModelDownloadWork> by lazy {
            workManager
                .getWorkInfosForUniqueWorkFlow(MODEL_DOWNLOAD_WORK_NAME)
                .map { infos -> mapDownloadWorkInfo(infos.firstOrNull()) }
                .stateIn(scope, SharingStarted.Eagerly, SpeechModelDownloadWork.Idle)
        }

        override fun startDownload(
            modelId: LocalSpeechModelId,
            preferInternalStorage: Boolean,
        ) {
            val request =
                OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    ).setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        RETRY_BACKOFF_SECONDS,
                        TimeUnit.SECONDS,
                    ).setInputData(
                        workDataOf(
                            ModelDownloadWorker.INPUT_PREFER_INTERNAL_STORAGE to preferInternalStorage,
                            ModelDownloadWorker.INPUT_MODEL_ID to modelId.persistedValue,
                        ),
                    ).build()
            // KEEP: never double-schedule while a download is pending/running; terminal work
            // (failed/succeeded/cancelled) does not block a new explicit start.
            workManager.enqueueUniqueWork(MODEL_DOWNLOAD_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        override fun cancelDownload() {
            // Partial temp files stay on disk; the next start resumes via HTTP Range.
            workManager.cancelUniqueWork(MODEL_DOWNLOAD_WORK_NAME)
        }

        private companion object {
            const val RETRY_BACKOFF_SECONDS = 30L
        }
    }

internal fun mapDownloadWorkInfo(info: WorkInfo?): SpeechModelDownloadWork =
    when (info?.state) {
        null, WorkInfo.State.CANCELLED -> SpeechModelDownloadWork.Idle
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> SpeechModelDownloadWork.Waiting
        WorkInfo.State.RUNNING -> mapRunningDownload(info.progress)
        WorkInfo.State.SUCCEEDED -> SpeechModelDownloadWork.Succeeded
        WorkInfo.State.FAILED ->
            SpeechModelDownloadWork.Failed(
                info.outputData.getString(ModelDownloadWorker.OUTPUT_ERROR) ?: "Download failed",
            )
    }

internal fun mapRunningDownload(progress: Data): SpeechModelDownloadWork.Running =
    SpeechModelDownloadWork.Running(
        file = progress.getString(ModelDownloadWorker.PROGRESS_FILE).orEmpty(),
        progress = progress.getFloat(ModelDownloadWorker.PROGRESS_FRACTION, 0f),
    )
