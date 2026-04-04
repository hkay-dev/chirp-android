package dev.chirpboard.app.feature.keyboard.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import androidx.core.app.NotificationCompat

internal object KeyboardForegroundNotification {

    fun start(
        service: InputMethodService,
        channelId: String,
        notificationId: Int,
        mainActivityClass: String
    ) {
        createChannel(service, channelId)

        val notificationIntent = try {
            Intent().setClassName(service, mainActivityClass)
        } catch (_: Exception) {
            Intent()
        }

        val pendingIntent = PendingIntent.getActivity(
            service,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(service, channelId)
            .setContentTitle("Chirp")
            .setContentText("Voice model loaded in memory")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        service.startForeground(notificationId, notification)
    }

    fun createChannel(
        service: InputMethodService,
        channelId: String
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            channelId,
            "Chirp Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps voice recognition model loaded in memory"
            setShowBadge(false)
        }

        val notificationManager = service.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}
