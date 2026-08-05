package dev.chirpboard.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.chirpboard.app.R
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.modelreadiness.VerificationTrigger
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.core.transcription.LocalSpeechModelId
import dev.chirpboard.app.core.transcription.LocalSpeechModelSelectionStore
import kotlinx.coroutines.CancellationException

/**
 * Long-running worker that downloads the ~660MB speech model as WorkManager unique work
 * (ERR-1): the transfer survives leaving the settings screen, process death, and reboots,
 * with a foreground progress notification while it runs. Transient failures (network drop,
 * server 5xx) retry with bounded exponential backoff (ERR-3) and resume via HTTP Range from
 * the partial file the downloader keeps on disk (ERR-2); non-retryable failures (checksum
 * mismatch, storage full) fail terminally and surface an explicit error for a manual retry.
 *
 * The SHA-256 verification chain lives in [ModelDownloader] and is unchanged.
 */
@HiltWorker
class ModelDownloadWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val modelDownloader: ModelDownloader,
        private val readinessGate: SpeechModelReadinessGate,
        private val transcriptionRecovery: TranscriptionRecovery,
        private val selectionStore: LocalSpeechModelSelectionStore,
    ) : CoroutineWorker(appContext, workerParams) {
        companion object {
            private const val TAG = "ModelDownloadWorker"

            const val INPUT_PREFER_INTERNAL_STORAGE = "prefer_internal_storage"
            const val INPUT_MODEL_ID = "model_id"
            const val PROGRESS_FRACTION = "progress_fraction"
            const val PROGRESS_FILE = "progress_file"
            const val OUTPUT_ERROR = "error"

            const val MODEL_DOWNLOAD_CHANNEL_ID = "model_download_progress"

            // 22xx range: recording uses 10xx, transcription/enhancement workers 20xx,
            // Obsidian export errors 21xx — keep the download ids collision-free.
            const val MODEL_DOWNLOAD_NOTIFICATION_ID = 2201
            const val MODEL_DOWNLOAD_RESULT_NOTIFICATION_ID = 2202

            /**
             * Bounded auto-retry budget (ERR-3): after this many attempts the work fails
             * terminally and the user must retry explicitly from the settings screen.
             */
            internal const val MAX_RUN_ATTEMPTS = 5

            private const val MAX_PROGRESS_PERCENT = 100

            /** True when a retryable error should be retried instead of failing terminally. */
            internal fun shouldRetry(
                retryable: Boolean,
                runAttemptCount: Int,
                maxRunAttempts: Int = MAX_RUN_ATTEMPTS,
            ): Boolean = retryable && runAttemptCount + 1 < maxRunAttempts
        }

        override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo(percent = 0)

        override suspend fun doWork(): Result {
            // On Android 12+ promoting to foreground can be refused when a retry fires while
            // the app is backgrounded (FGS-start restriction). The download still proceeds as
            // regular work in that case — never crash the IME-shared process over the banner.
            try {
                setForeground(createForegroundInfo(percent = 0))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Continuing model download without foreground promotion", e)
            }

            val preferInternalStorage = inputData.getBoolean(INPUT_PREFER_INTERNAL_STORAGE, false)
            val modelId = LocalSpeechModelId.fromPersistedValue(inputData.getString(INPUT_MODEL_ID))
            var lastNotifiedPercent = -1
            var result: Result = Result.failure(workDataOf(OUTPUT_ERROR to "Download did not produce a result"))

            modelDownloader.downloadModelFlow(modelId, preferInternalStorage).collect { state ->
                when (state) {
                    is ModelDownloader.DownloadState.Progress -> {
                        val fraction =
                            if (state.totalBytes > 0) {
                                state.bytesDownloaded.toFloat() / state.totalBytes.toFloat()
                            } else {
                                0f
                            }
                        val percent = (fraction * MAX_PROGRESS_PERCENT).toInt()
                        if (percent != lastNotifiedPercent) {
                            lastNotifiedPercent = percent
                            setProgress(
                                workDataOf(
                                    PROGRESS_FRACTION to fraction,
                                    PROGRESS_FILE to state.file,
                                ),
                            )
                            updateProgressNotification(percent)
                        }
                    }

                    ModelDownloader.DownloadState.Complete -> {
                        onDownloadComplete(modelId)
                        result = Result.success()
                    }

                    is ModelDownloader.DownloadState.Error -> {
                        result =
                            if (shouldRetry(state.retryable, runAttemptCount)) {
                                Log.w(TAG, "Retryable download error (attempt ${runAttemptCount + 1}): ${state.message}")
                                Result.retry()
                            } else {
                                Log.e(TAG, "Terminal download error: ${state.message}")
                                postResultNotification(
                                    title = applicationContext.getString(R.string.model_download_failed_title),
                                    text = state.message,
                                )
                                Result.failure(workDataOf(OUTPUT_ERROR to state.message))
                            }
                    }
                }
            }

            return result
        }

        private suspend fun onDownloadComplete(modelId: LocalSpeechModelId) {
            // Same post-download chain the settings ViewModel used to drive: invalidate the
            // cached gate first so verification actually checks the now-present model
            // (otherwise a pre-download Unavailable sticks), then recover recordings parked
            // on the missing model. Runs here so it happens even when no UI is alive.
            if (selectionStore.selectedModel.value == modelId) {
                readinessGate.invalidate()
                readinessGate.verifyIfNeeded(VerificationTrigger.MODEL_DOWNLOAD)
                try {
                    transcriptionRecovery.recoverRecordingsWaitingForModel()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Startup recovery re-detects these recordings; never fail a finished download.
                    Log.e(TAG, "Failed to recover recordings waiting for the model", e)
                }
            }
            postResultNotification(
                title = applicationContext.getString(R.string.model_download_complete_title),
                text = applicationContext.getString(R.string.model_download_complete_text),
            )
        }

        private fun createForegroundInfo(percent: Int): ForegroundInfo {
            ensureChannel()
            return ForegroundInfo(
                MODEL_DOWNLOAD_NOTIFICATION_ID,
                buildProgressNotification(percent),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }

        private fun buildProgressNotification(percent: Int): android.app.Notification =
            NotificationCompat
                .Builder(applicationContext, MODEL_DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_download)
                .setContentTitle(applicationContext.getString(R.string.model_download_notification_title))
                .setContentText(
                    applicationContext.getString(R.string.model_download_notification_progress, percent),
                ).setProgress(MAX_PROGRESS_PERCENT, percent, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(launchAppPendingIntent())
                .build()

        private fun updateProgressNotification(percent: Int) {
            val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
            try {
                notificationManager.notify(MODEL_DOWNLOAD_NOTIFICATION_ID, buildProgressNotification(percent))
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not update download progress notification", e)
            }
        }

        private fun postResultNotification(
            title: String,
            text: String,
        ) {
            ensureChannel()
            val notification =
                NotificationCompat
                    .Builder(applicationContext, MODEL_DOWNLOAD_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notif_download)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(launchAppPendingIntent())
                    .build()
            val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
            try {
                notificationManager.notify(MODEL_DOWNLOAD_RESULT_NOTIFICATION_ID, notification)
            } catch (e: SecurityException) {
                // POST_NOTIFICATIONS denied: the settings screen still shows the same state.
                Log.w(TAG, "Could not post download result notification", e)
            }
        }

        private fun launchAppPendingIntent(): PendingIntent? {
            val launchIntent =
                applicationContext.packageManager
                    .getLaunchIntentForPackage(applicationContext.packageName)
                    ?.apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }
                    ?: return null
            return PendingIntent.getActivity(
                applicationContext,
                MODEL_DOWNLOAD_NOTIFICATION_ID,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun ensureChannel() {
            val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
            if (notificationManager.getNotificationChannel(MODEL_DOWNLOAD_CHANNEL_ID) != null) {
                return
            }
            val channel =
                NotificationChannel(
                    MODEL_DOWNLOAD_CHANNEL_ID,
                    applicationContext.getString(R.string.model_download_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = applicationContext.getString(R.string.model_download_channel_description)
                    setShowBadge(false)
                }
            notificationManager.createNotificationChannel(channel)
        }
    }
