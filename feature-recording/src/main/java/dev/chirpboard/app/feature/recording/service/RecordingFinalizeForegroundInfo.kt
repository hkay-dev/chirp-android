package dev.chirpboard.app.feature.recording.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import dev.chirpboard.app.feature.recording.R

private const val FINALIZE_NOTIFICATION_ID = 0x52464301
private const val FINALIZE_CHANNEL_ID = "recording_finalize"

internal fun buildRecordingFinalizeForegroundInfo(context: Context): ForegroundInfo {
    createFinalizeNotificationChannel(context)
    val notification =
        NotificationCompat.Builder(context, FINALIZE_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.rec_finalize_notification_title))
            .setContentText(context.getString(R.string.rec_finalize_notification_body))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    return ForegroundInfo(FINALIZE_NOTIFICATION_ID, notification)
}

private fun createFinalizeNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    val channel =
        NotificationChannel(
            FINALIZE_CHANNEL_ID,
            context.getString(R.string.rec_finalize_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
    manager.createNotificationChannel(channel)
}
