package dev.chirpboard.app.feature.recording.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import dev.chirpboard.app.core.recording.RecordingServiceCommands
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.util.formatAsDuration
import dev.chirpboard.app.feature.recording.R
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingNotificationFactory
    @Inject
    constructor() {
        /**
         * The launch/action PendingIntents are identical for the life of the process, so
         * they are created once and reused: rebuilding them per notification post costs a
         * binder round-trip into system_server each (PRF-5).
         */
        @Volatile
        private var cachedLaunchPendingIntent: PendingIntent? = null

        @Volatile
        private var cachedStopPendingIntent: PendingIntent? = null

        @Volatile
        private var cachedResumePendingIntent: PendingIntent? = null

        @Volatile
        private var cachedPausePendingIntent: PendingIntent? = null

        fun ensureChannel(context: Context) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.rec_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.rec_notification_channel_description)
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            // Installs that ran a v1 build keep an orphaned channel in system settings forever.
            notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }

        fun createStartingNotification(service: Service): Notification {
            val contentPendingIntent = launchPendingIntent(service)
            return NotificationCompat
                .Builder(service, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_mic)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPendingIntent)
                .setColorized(true)
                .setColor(android.graphics.Color.parseColor(BRAND_RECORDING_COLOR))
                .setContentTitle(service.getString(R.string.rec_notification_starting_title))
                .setContentText(service.getString(R.string.rec_notification_starting_text))
                .build()
        }

        /**
         * Builds the live recording notification. The elapsed timer is rendered by the
         * system chronometer ([NotificationCompat.Builder.setUsesChronometer]) so the
         * notification only needs re-posting on state transitions and warning changes —
         * never on a per-second loop. [statusText] carries transient warnings (silence,
         * low storage, focus-pause reason) shown as the content line.
         */
        fun createRecordingNotification(
            service: Service,
            recordingStateManager: RecordingStateManager,
            statusText: String? = null,
        ): Notification {
            val state = recordingStateManager.state.value
            val isPaused = state is RecordingState.Paused
            val duration = recordingStateManager.getCurrentDurationMs()
            val contentPendingIntent = launchPendingIntent(service)
            val donePendingIntent = stopActionPendingIntent(service)

            val builder =
                NotificationCompat
                    .Builder(service, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notif_mic)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(contentPendingIntent)
                    .setColorized(true)
                    .setColor(android.graphics.Color.parseColor(BRAND_RECORDING_COLOR))

            if (isPaused) {
                builder.setContentTitle(service.getString(R.string.rec_notification_paused_title))
                builder.setContentText(statusText ?: duration.formatAsDuration())
                builder.addAction(
                    R.drawable.ic_notif_resume,
                    service.getString(R.string.rec_notification_action_resume),
                    resumeActionPendingIntent(service),
                )
                builder.addAction(
                    R.drawable.ic_notif_done,
                    service.getString(R.string.rec_notification_action_done),
                    donePendingIntent,
                )
            } else {
                builder.setContentTitle(service.getString(R.string.rec_notification_recording_title))
                statusText?.let { builder.setContentText(it) }
                builder.setUsesChronometer(true)
                builder.setWhen(System.currentTimeMillis() - duration)
                builder.addAction(
                    R.drawable.ic_notif_pause,
                    service.getString(R.string.rec_notification_action_pause),
                    pauseActionPendingIntent(service),
                )
                builder.addAction(
                    R.drawable.ic_notif_done,
                    service.getString(R.string.rec_notification_action_done),
                    donePendingIntent,
                )
            }

            return builder.build()
        }

        fun updateRecordingNotification(
            service: Service,
            recordingStateManager: RecordingStateManager,
            statusText: String? = null,
        ) {
            val notification = createRecordingNotification(service, recordingStateManager, statusText)
            service.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }

        /**
         * Transient, auto-expiring feedback for a restart refused while a stop is saving
         * the previous recording. Posted on its own id so it never clobbers the ongoing
         * recording notification, and never as a foreground notification.
         */
        fun notifyRestartRefused(service: Service) {
            val notification =
                NotificationCompat
                    .Builder(service, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notif_mic)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(launchPendingIntent(service))
                    .setContentTitle(service.getString(R.string.rec_notification_restart_refused_title))
                    .setContentText(service.getString(R.string.rec_notification_restart_refused_text))
                    .setTimeoutAfter(TRANSIENT_NOTIFICATION_TIMEOUT_MS)
                    .build()
            service
                .getSystemService(NotificationManager::class.java)
                .notify(RESTART_REFUSED_NOTIFICATION_ID, notification)
        }

        /**
         * Transient explanation for a recording the service stopped on its own (storage
         * critical, permanent focus loss, device loss, capture death). The foreground
         * notification disappears with the stop, so this is the only system-surface clue
         * about WHY the session ended; it survives until tapped or timed out.
         */
        fun notifyAutoStopped(
            service: Service,
            reason: RecordingAutoStopReason,
            detail: String? = null,
        ) {
            val reasonText =
                when (reason) {
                    RecordingAutoStopReason.STORAGE_CRITICAL -> service.getString(R.string.rec_auto_stop_storage)
                    RecordingAutoStopReason.FOCUS_LOST -> service.getString(R.string.rec_auto_stop_focus)
                    RecordingAutoStopReason.INPUT_DEVICE_LOST ->
                        if (detail.isNullOrBlank()) {
                            service.getString(R.string.rec_auto_stop_device)
                        } else {
                            service.getString(R.string.rec_auto_stop_device_named, detail)
                        }
                    RecordingAutoStopReason.CAPTURE_ERROR -> service.getString(R.string.rec_auto_stop_capture_error)
                }
            val notification =
                NotificationCompat
                    .Builder(service, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notif_mic)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(launchPendingIntent(service))
                    .setContentTitle(service.getString(R.string.rec_auto_stop_title))
                    .setContentText(reasonText)
                    .setTimeoutAfter(AUTO_STOP_NOTIFICATION_TIMEOUT_MS)
                    .build()
            service
                .getSystemService(NotificationManager::class.java)
                .notify(AUTO_STOP_NOTIFICATION_ID, notification)
        }

        /**
         * Terminal failure surface for the background finalize: the stopped recording
         * could not be saved and nothing recoverable remains on disk. The foreground
         * service and its notification are gone by the time this runs, so without this
         * post the recording simply vanishes with no explanation. No timeout: losing a
         * recording must stay visible until the user dismisses it.
         */
        fun notifySaveFailed(context: Context) {
            ensureChannel(context)
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notif_mic)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(launchPendingIntent(context))
                    .setContentTitle(context.getString(R.string.rec_save_failed_title))
                    .setContentText(context.getString(R.string.rec_save_failed_text))
                    .build()
            context
                .getSystemService(NotificationManager::class.java)
                .notify(SAVE_FAILED_NOTIFICATION_ID, notification)
        }

        @VisibleForTesting
        internal fun launchPendingIntent(context: Context): PendingIntent? {
            cachedLaunchPendingIntent?.let { return it }
            val launchIntent =
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    // addFlags keeps FLAG_ACTIVITY_NEW_TASK from getLaunchIntentForPackage;
                    // assignment used to strip it and rely on the platform force-adding it.
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            return launchIntent?.let {
                PendingIntent
                    .getActivity(
                        context.applicationContext,
                        0,
                        it,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ).also { pending -> cachedLaunchPendingIntent = pending }
            }
        }

        @VisibleForTesting
        internal fun stopActionPendingIntent(service: Service): PendingIntent =
            cachedStopPendingIntent ?: serviceActionPendingIntent(
                service,
                STOP_REQUEST_CODE,
                RecordingServiceCommands.ACTION_STOP_RECORDING,
            ).also { cachedStopPendingIntent = it }

        @VisibleForTesting
        internal fun resumeActionPendingIntent(service: Service): PendingIntent =
            cachedResumePendingIntent ?: serviceActionPendingIntent(
                service,
                RESUME_REQUEST_CODE,
                RecordingServiceCommands.ACTION_RESUME_RECORDING,
            ).also { cachedResumePendingIntent = it }

        @VisibleForTesting
        internal fun pauseActionPendingIntent(service: Service): PendingIntent =
            cachedPausePendingIntent ?: serviceActionPendingIntent(
                service,
                PAUSE_REQUEST_CODE,
                RecordingServiceCommands.ACTION_PAUSE_RECORDING,
            ).also { cachedPausePendingIntent = it }

        private fun serviceActionPendingIntent(
            service: Service,
            requestCode: Int,
            action: String,
        ): PendingIntent {
            val context: Context = service.applicationContext
            val intent =
                Intent(context, RecordingService::class.java).apply {
                    this.action = action
                }
            return PendingIntent.getService(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        companion object {
            const val CHANNEL_ID = "recording_channel_v2"
            private const val LEGACY_CHANNEL_ID = "recording_channel"
            const val NOTIFICATION_ID = 1001
            const val RESTART_REFUSED_NOTIFICATION_ID = 1002
            const val AUTO_STOP_NOTIFICATION_ID = 1003
            const val SAVE_FAILED_NOTIFICATION_ID = 1004
            private const val TRANSIENT_NOTIFICATION_TIMEOUT_MS = 8_000L
            private const val AUTO_STOP_NOTIFICATION_TIMEOUT_MS = 30_000L
            private const val BRAND_RECORDING_COLOR = "#D32F2F"
            private const val STOP_REQUEST_CODE = 1
            private const val RESUME_REQUEST_CODE = 2
            private const val PAUSE_REQUEST_CODE = 3
        }
    }
