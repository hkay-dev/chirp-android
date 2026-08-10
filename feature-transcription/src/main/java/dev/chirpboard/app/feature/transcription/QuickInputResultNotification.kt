package dev.chirpboard.app.feature.transcription

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
 * A fixed notification id replaces older results. Tapping copies the preferred text (via
 * [QuickInputCopyActivity], which holds window focus for one frame so the write is honored)
 * and leaves the card in place until its configured timeout. No editor state is touched.
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
            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
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
                                QuickInputCopyActivity.ACTION_COPY_AI
                            } else {
                                QuickInputCopyActivity.ACTION_COPY_RAW
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
                        action = QuickInputCopyActivity.ACTION_COPY_RAW,
                        requestCode = COPY_RAW_REQUEST_CODE,
                        text = content.rawText,
                    ),
                ).apply {
                    content.processedText?.let { processed ->
                        addAction(
                            0,
                            context.getString(R.string.quick_input_result_copy_ai),
                            copyPendingIntent(
                                action = QuickInputCopyActivity.ACTION_COPY_AI,
                                requestCode = COPY_AI_REQUEST_CODE,
                                text = processed,
                            ),
                        )
                    }
                }.build()
        }

        // An activity, not a broadcast: the clipboard write needs window focus to be
        // honored on every build (see QuickInputCopyActivity).
        private fun copyPendingIntent(
            action: String,
            requestCode: Int,
            text: String,
        ): PendingIntent =
            PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, QuickInputCopyActivity::class.java)
                    .setAction(action)
                    .putExtra(QuickInputCopyActivity.EXTRA_TEXT, text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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
