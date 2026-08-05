package dev.chirpboard.app.feature.transcription

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal const val QUICK_INPUT_RESULT_TIMEOUT_MS = 30_000L
internal const val QUICK_INPUT_RESULT_NOTIFICATION_ID = 0x43485250
private const val QUICK_INPUT_RESULT_CHANNEL_ID = "quick_input_results_v1"
private const val COPY_RAW_REQUEST_CODE = 4_101
private const val COPY_AI_REQUEST_CODE = 4_102
private const val PASTE_RAW_REQUEST_CODE = 4_103
private const val PASTE_AI_REQUEST_CODE = 4_104
private const val TAG = "QuickInputResultNotif"

/** App-level bridge used when a verified failed handoff can retry through accessibility. */
interface QuickInputPasteHandler {
    fun requestPaste(
        sessionId: Long,
        useProcessedText: Boolean,
    ): Boolean
}

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

/**
 * Keeps the latest successful quick-input result reachable when a caller fails to insert it.
 * A fixed notification id replaces older results, and Android removes the notification after
 * [QUICK_INPUT_RESULT_TIMEOUT_MS]. No focus or editor state in the caller is touched.
 */
@Singleton
class QuickInputResultNotificationPublisher
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun show(
            rawText: String,
            processedText: String?,
            pasteSessionId: Long? = null,
        ): Boolean {
            val content = quickInputNotificationContent(rawText, processedText) ?: return false
            val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return false
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

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
                    buildNotification(content, pasteSessionId),
                )
                true
            } catch (error: RuntimeException) {
                // Result delivery must never depend on notification availability.
                Log.e(TAG, "Could not post latest quick-input notification", error)
                false
            }
        }

        fun cancel() {
            NotificationManagerCompat.from(context).cancel(QUICK_INPUT_RESULT_NOTIFICATION_ID)
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
            pasteSessionId: Long?,
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
                .setContentTitle(
                    context.getString(
                        if (pasteSessionId == null) {
                            R.string.quick_input_result_notification_title
                        } else {
                            R.string.quick_input_result_paste_notification_title
                        },
                    ),
                )
                .setContentText(content.processedText ?: content.rawText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setTimeoutAfter(QUICK_INPUT_RESULT_TIMEOUT_MS)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(buildPublicVersion())
                .setLocalOnly(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(
                    resultActionPendingIntent(
                        content = content,
                        pasteSessionId = pasteSessionId,
                        useProcessedText = content.processedText != null,
                        paste = pasteSessionId != null,
                    ),
                )
                .addAction(
                    0,
                    context.getString(R.string.quick_input_result_copy_raw),
                    resultActionPendingIntent(
                        content = content,
                        pasteSessionId = pasteSessionId,
                        useProcessedText = false,
                        paste = false,
                    ),
                ).apply {
                    content.processedText?.let {
                        addAction(
                            0,
                            context.getString(R.string.quick_input_result_copy_ai),
                            resultActionPendingIntent(
                                content = content,
                                pasteSessionId = pasteSessionId,
                                useProcessedText = true,
                                paste = false,
                            ),
                        )
                    }
                }.build()
        }

        private fun resultActionPendingIntent(
            content: QuickInputNotificationContent,
            pasteSessionId: Long?,
            useProcessedText: Boolean,
            paste: Boolean,
        ): PendingIntent {
            val action =
                when {
                    paste && useProcessedText -> QuickInputResultCopyReceiver.ACTION_PASTE_AI
                    paste -> QuickInputResultCopyReceiver.ACTION_PASTE_RAW
                    useProcessedText -> QuickInputResultCopyReceiver.ACTION_COPY_AI
                    else -> QuickInputResultCopyReceiver.ACTION_COPY_RAW
                }
            val requestCode =
                when (action) {
                    QuickInputResultCopyReceiver.ACTION_PASTE_AI -> PASTE_AI_REQUEST_CODE
                    QuickInputResultCopyReceiver.ACTION_PASTE_RAW -> PASTE_RAW_REQUEST_CODE
                    QuickInputResultCopyReceiver.ACTION_COPY_AI -> COPY_AI_REQUEST_CODE
                    else -> COPY_RAW_REQUEST_CODE
                }
            val text = if (useProcessedText) content.processedText ?: content.rawText else content.rawText
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, QuickInputResultCopyReceiver::class.java)
                    .setAction(action)
                    .putExtra(QuickInputResultCopyReceiver.EXTRA_TEXT, text)
                    .apply {
                        pasteSessionId?.let { putExtra(QuickInputResultCopyReceiver.EXTRA_PASTE_SESSION_ID, it) }
                    },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
            )
        }

        private fun buildPublicVersion(): Notification =
            NotificationCompat
                .Builder(context, QUICK_INPUT_RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_transcription)
                .setContentTitle(context.getString(R.string.quick_input_result_notification_title))
                .setContentText(context.getString(R.string.quick_input_result_public_body))
                .build()
    }

/** Pastes through a live accessibility session or falls back to a normal clipboard copy. */
@AndroidEntryPoint
class QuickInputResultCopyReceiver : BroadcastReceiver() {
    @Inject lateinit var pasteHandler: QuickInputPasteHandler

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val useProcessedText = intent.action == ACTION_COPY_AI || intent.action == ACTION_PASTE_AI
        val isPaste = intent.action == ACTION_PASTE_RAW || intent.action == ACTION_PASTE_AI
        if (!isPaste && intent.action != ACTION_COPY_RAW && intent.action != ACTION_COPY_AI) return
        val text = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
        if (isPaste) {
            val sessionId = intent.getLongExtra(EXTRA_PASTE_SESSION_ID, MISSING_SESSION_ID)
            if (
                sessionId != MISSING_SESSION_ID &&
                pasteHandler.requestPaste(sessionId, useProcessedText)
            ) {
                return
            }
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            Toast.makeText(context, R.string.quick_input_result_copy_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val clip = ClipData.newPlainText(context.getString(R.string.transcription_title), text)
        clipboard.setPrimaryClip(clip)
        NotificationManagerCompat.from(context).cancel(QUICK_INPUT_RESULT_NOTIFICATION_ID)
        Toast.makeText(
            context,
            if (useProcessedText) R.string.quick_input_result_copied_ai else R.string.quick_input_result_copied_raw,
            Toast.LENGTH_SHORT,
        ).show()
    }

    companion object {
        internal const val ACTION_COPY_RAW = "dev.chirpboard.app.action.COPY_QUICK_INPUT_RAW"
        internal const val ACTION_COPY_AI = "dev.chirpboard.app.action.COPY_QUICK_INPUT_AI"
        internal const val ACTION_PASTE_RAW = "dev.chirpboard.app.action.PASTE_QUICK_INPUT_RAW"
        internal const val ACTION_PASTE_AI = "dev.chirpboard.app.action.PASTE_QUICK_INPUT_AI"
        internal const val EXTRA_TEXT = "dev.chirpboard.app.extra.QUICK_INPUT_TEXT"
        internal const val EXTRA_PASTE_SESSION_ID = "dev.chirpboard.app.extra.QUICK_INPUT_PASTE_SESSION_ID"
        private const val MISSING_SESSION_ID = -1L
    }
}
