package dev.chirpboard.app.feature.recording.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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
        fun ensureChannel(service: Service) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Recording",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows when recording is in progress"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
            service.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
                .setColor(android.graphics.Color.parseColor("#D32F2F"))
                .setContentTitle(service.getString(R.string.rec_notification_starting_title))
                .setContentText(service.getString(R.string.rec_notification_starting_text))
                .build()
        }

        fun createRecordingNotification(
            service: Service,
            recordingStateManager: RecordingStateManager,
        ): Notification {
            val state = recordingStateManager.state.value
            val isPaused = state is RecordingState.Paused
            val duration = recordingStateManager.getCurrentDurationMs()
            val durationText = duration.formatAsDuration()
            val contentPendingIntent = launchPendingIntent(service)

            val doneIntent =
                Intent(service, RecordingService::class.java).apply {
                    action = RecordingServiceCommands.ACTION_STOP_RECORDING
                }
            val donePendingIntent =
                PendingIntent.getService(
                    service,
                    1,
                    doneIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val builder =
                NotificationCompat
                    .Builder(service, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notif_mic)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(contentPendingIntent)
                    .setColorized(true)
                    .setColor(android.graphics.Color.parseColor("#D32F2F"))

            if (isPaused) {
                builder.setContentTitle("Recording paused")
                builder.setContentText(durationText)
                val resumeIntent =
                    Intent(service, RecordingService::class.java).apply {
                        action = RecordingServiceCommands.ACTION_RESUME_RECORDING
                    }
                val resumePendingIntent =
                    PendingIntent.getService(
                        service,
                        2,
                        resumeIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                builder.addAction(R.drawable.ic_notif_resume, "Resume", resumePendingIntent)
                builder.addAction(R.drawable.ic_notif_done, "Done", donePendingIntent)
            } else {
                builder.setContentTitle("Recording")
                builder.setContentText(durationText)
                builder.setUsesChronometer(true)
                builder.setWhen(System.currentTimeMillis() - duration)
                val pauseIntent =
                    Intent(service, RecordingService::class.java).apply {
                        action = RecordingServiceCommands.ACTION_PAUSE_RECORDING
                    }
                val pausePendingIntent =
                    PendingIntent.getService(
                        service,
                        3,
                        pauseIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                builder.addAction(R.drawable.ic_notif_pause, "Pause", pausePendingIntent)
                builder.addAction(R.drawable.ic_notif_done, "Done", donePendingIntent)
            }

            return builder.build()
        }

        fun updateRecordingNotification(
            service: Service,
            recordingStateManager: RecordingStateManager,
        ) {
            val notification = createRecordingNotification(service, recordingStateManager)
            service.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }

        private fun launchPendingIntent(service: Service): PendingIntent? {
            val launchIntent =
                service.packageManager.getLaunchIntentForPackage(service.packageName)?.apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            return launchIntent?.let {
                PendingIntent.getActivity(
                    service,
                    0,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        }

        companion object {
            const val CHANNEL_ID = "recording_channel_v2"
            const val NOTIFICATION_ID = 1001
        }
    }
