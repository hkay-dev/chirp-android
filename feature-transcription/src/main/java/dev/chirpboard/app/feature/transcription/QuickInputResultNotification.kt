package dev.chirpboard.app.feature.transcription

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.preferences.DEFAULT_QUICK_INPUT_NOTIFICATION_TIMEOUT_MS
import dev.chirpboard.app.core.preferences.KeyboardPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

internal const val QUICK_INPUT_RESULT_TIMEOUT_MS = DEFAULT_QUICK_INPUT_NOTIFICATION_TIMEOUT_MS
internal const val QUICK_INPUT_RESULT_NOTIFICATION_ID = 0x43485250
private const val QUICK_INPUT_RESULT_CHANNEL_ID = "quick_input_results_v1"
private const val COPY_PREFERRED_REQUEST_CODE = 4_100
private const val COPY_RAW_REQUEST_CODE = 4_101
private const val COPY_AI_REQUEST_CODE = 4_102
private const val TAG = "QuickInputResultNotif"

internal data class QuickInputNotificationContent(
    val rawText: String,
    val processedText: String?,
)

internal fun quickInputNotificationContent(
    rawText: String,
    processedText: String?,
): QuickInputNotificationContent? {
    val raw = rawText.trim()
    if (raw.isEmpty()) return null
    val processed = processedText?.trim()?.takeIf { it.isNotEmpty() && it != raw }
    return QuickInputNotificationContent(rawText = raw, processedText = processed)
}

internal fun quickInputNotificationExpandedText(
    content: QuickInputNotificationContent,
    rawLabel: String,
    aiLabel: String,
): String =
    content.processedText?.let { processed ->
        "$aiLabel\n$processed\n\n$rawLabel\n${content.rawText}"
    } ?: content.rawText

internal fun quickInputNotificationPreferredText(content: QuickInputNotificationContent): String =
    content.processedText ?: content.rawText

/**
 * Keeps the latest successful quick-input result reachable when a caller fails to insert it.
 * A fixed notification id replaces older results. Tapping copies the preferred text and leaves
 * the card in place until its configured timeout. No caller focus or editor state is touched.
 */
@Singleton
class QuickInputResultNotificationPublisher
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val keyboardPreferences: KeyboardPreferences,
    ) {
        suspend fun show(
            rawText: String,
            processedText: String?,
        ): Boolean {
            val content = quickInputNotificationContent(rawText, processedText) ?: return false
            val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return false
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            val timeoutMs =
                try {
                    keyboardPreferences.quickInputNotificationTimeoutMs.first()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Could not read quick-input notification timeout; using default", error)
                    QUICK_INPUT_RESULT_TIMEOUT_MS
                }

            return try {
                ensureChannel(notificationManager)
                if (
                    notificationManager.getNotificationChannel(QUICK_INPUT_RESULT_CHANNEL_ID)?.importance ==
                    NotificationManager.IMPORTANCE_NONE
                ) {
                    return false
                }

                NotificationManagerCompat.from(context).notify(
                    QUICK_INPUT_RESULT_NOTIFICATION_ID,
                    buildNotification(content, timeoutMs),
                )
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Result delivery must never depend on notification availability.
                Log.e(TAG, "Could not post latest quick-input notification", error)
                false
            }
        }

        private fun ensureChannel(notificationManager: NotificationManager) {
            if (notificationManager.getNotificationChannel(QUICK_INPUT_RESULT_CHANNEL_ID) != null) return
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    QUICK_INPUT_RESULT_CHANNEL_ID,
                    context.getString(R.string.quick_input_result_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.quick_input_result_channel_description)
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
        }

        private fun buildNotification(
            content: QuickInputNotificationContent,
            timeoutMs: Long,
        ): Notification {
            val expandedText =
                quickInputNotificationExpandedText(
                    content = content,
                    rawLabel = context.getString(R.string.quick_input_result_raw_label),
                    aiLabel = context.getString(R.string.quick_input_result_ai_label),
                )
            return NotificationCompat
                .Builder(context, QUICK_INPUT_RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_transcription)
                .setContentTitle(context.getString(R.string.quick_input_result_notification_title))
                .setContentText(quickInputNotificationPreferredText(content))
                .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
                .setContentIntent(
                    copyPendingIntent(
                        action =
                            if (content.processedText != null) {
                                QuickInputResultCopyReceiver.ACTION_COPY_AI
                            } else {
                                QuickInputResultCopyReceiver.ACTION_COPY_RAW
                        },
                        requestCode = COPY_PREFERRED_REQUEST_CODE,
                        text = quickInputNotificationPreferredText(content),
                    ),
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setTimeoutAfter(timeoutMs)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(buildPublicVersion())
                .setLocalOnly(true)
                .setOngoing(true)
                .setAutoCancel(false)
                .addAction(
                    0,
                    context.getString(R.string.quick_input_result_copy_raw),
                    copyPendingIntent(
                        action = QuickInputResultCopyReceiver.ACTION_COPY_RAW,
                        requestCode = COPY_RAW_REQUEST_CODE,
                        text = content.rawText,
                    ),
                ).apply {
                    content.processedText?.let { processed ->
                        addAction(
                            0,
                            context.getString(R.string.quick_input_result_copy_ai),
                            copyPendingIntent(
                                action = QuickInputResultCopyReceiver.ACTION_COPY_AI,
                                requestCode = COPY_AI_REQUEST_CODE,
                                text = processed,
                            ),
                        )
                    }
                }.build()
        }

        private fun copyPendingIntent(
            action: String,
            requestCode: Int,
            text: String,
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, QuickInputResultCopyReceiver::class.java)
                    .setAction(action)
                    .putExtra(QuickInputResultCopyReceiver.EXTRA_TEXT, text),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun buildPublicVersion(): Notification =
            NotificationCompat
                .Builder(context, QUICK_INPUT_RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_transcription)
                .setContentTitle(context.getString(R.string.quick_input_result_notification_title))
                .setContentText(context.getString(R.string.quick_input_result_public_body))
                .build()
    }

/** Copies one short-lived result held by an immutable, app-private notification action. */
class QuickInputResultCopyReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val copyAi = intent.action == ACTION_COPY_AI
        if (!copyAi && intent.action != ACTION_COPY_RAW) return
        val text = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            Toast.makeText(context, R.string.quick_input_result_copy_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val clip = ClipData.newPlainText(context.getString(R.string.transcription_title), text)
        clip.description.extras =
            PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        try {
            clipboard.setPrimaryClip(clip)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not copy quick-input result", error)
            Toast.makeText(context, R.string.quick_input_result_copy_failed, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(
            context,
            if (copyAi) R.string.quick_input_result_copied_ai else R.string.quick_input_result_copied_raw,
            Toast.LENGTH_SHORT,
        ).show()
    }

    companion object {
        internal const val ACTION_COPY_RAW = "dev.chirpboard.app.action.COPY_QUICK_INPUT_RAW"
        internal const val ACTION_COPY_AI = "dev.chirpboard.app.action.COPY_QUICK_INPUT_AI"
        internal const val EXTRA_TEXT = "dev.chirpboard.app.extra.QUICK_INPUT_TEXT"
    }
}
