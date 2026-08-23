package dev.chirpboard.app.feature.transcription

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.transcription.CloudTranscriptionConfigurationStatus
import dev.chirpboard.app.core.transcription.GOOGLE_CLOUD_CHIRP_3_MAX_AUDIO_BYTES
import dev.chirpboard.app.core.transcription.GOOGLE_CLOUD_CHIRP_3_MAX_DURATION_MS
import dev.chirpboard.app.core.transcription.RecognizedWordTiming
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.core.transcription.ACTION_OPEN_TRANSCRIPTION_RECORDING
import dev.chirpboard.app.core.transcription.EXTRA_TRANSCRIPTION_RECORDING_ID
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.entity.Transcript
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

internal const val TRANSCRIPTION_MAX_RETRY_COUNT = 3
internal const val TRANSCRIPTION_MAX_ACTIVE_WAIT_MS = 30 * 60 * 1000L
internal const val TRANSCRIPTION_MIN_ACTIVE_WAIT_MS = 5 * 60 * 1000L
internal const val TRANSCRIPTION_ACTIVE_WAIT_PER_MINUTE_MS = 60_000L
internal const val TRANSCRIPTION_FOREGROUND_NOTIFICATION_ID = 2001
internal const val TRANSCRIPTION_FOREGROUND_CHANNEL_ID = "transcription_progress"
internal const val ENHANCEMENT_FOREGROUND_NOTIFICATION_ID = 2002
internal const val ENHANCEMENT_FOREGROUND_CHANNEL_ID = "enhancement_progress"
private const val COPY_RAW_REQUEST_CODE_MASK = 0x13579BDF
private const val COPY_AI_REQUEST_CODE_MASK = 0x2468ACE
private const val RETRY_REQUEST_CODE_MASK = 0x5A5A5A5

// I18N-08: notification titles/bodies/channel names live in strings.xml, not Kotlin literals.
internal fun transcriptionProgressNotificationTitle(context: Context): String =
    context.getString(R.string.transcription_progress_notification_title)

internal fun enhancementProgressNotificationTitle(context: Context): String =
    context.getString(R.string.enhancement_progress_notification_title)

internal fun backgroundWorkerForegroundServiceType(): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

internal fun buildTranscriptionProgressNotification(context: Context): Notification {
    ensureTranscriptionProgressChannel(context)
    return NotificationCompat
        .Builder(context, TRANSCRIPTION_FOREGROUND_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(transcriptionProgressNotificationTitle(context))
        .setContentText(context.getString(R.string.transcription_progress_notification_body))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()
}

internal fun buildTranscriptionForegroundInfo(context: Context): ForegroundInfo {
    val notification = buildTranscriptionProgressNotification(context)
    return ForegroundInfo(
        TRANSCRIPTION_FOREGROUND_NOTIFICATION_ID,
        notification,
        backgroundWorkerForegroundServiceType(),
    )
}

internal fun buildEnhancementProgressNotification(context: Context): Notification {
    ensureEnhancementProgressChannel(context)
    return NotificationCompat
        .Builder(context, ENHANCEMENT_FOREGROUND_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(enhancementProgressNotificationTitle(context))
        .setContentText(context.getString(R.string.enhancement_progress_notification_body))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()
}

internal fun buildEnhancementForegroundInfo(context: Context): ForegroundInfo {
    val notification = buildEnhancementProgressNotification(context)
    return ForegroundInfo(
        ENHANCEMENT_FOREGROUND_NOTIFICATION_ID,
        notification,
        backgroundWorkerForegroundServiceType(),
    )
}

/**
 * PIPE-01/PIPE-02: promote a worker to dataSync foreground when the platform allows it.
 * Workers in this pipeline usually start while the app is deep in the background, where an
 * unguarded setForeground throws [ForegroundServiceStartNotAllowedException] and would fail
 * the run outright; in that case the worker simply continues without foreground (short jobs
 * fit the normal execution window, long jobs are re-attached by startup recovery and the
 * reconciler). IllegalStateException covers the already-stopped-worker race the same way.
 */
internal suspend fun CoroutineWorker.trySetWorkerForeground(
    foregroundInfo: ForegroundInfo,
    logTag: String,
) {
    try {
        setForeground(foregroundInfo)
    } catch (e: ForegroundServiceStartNotAllowedException) {
        Log.w(logTag, "Foreground start not allowed; continuing in background", e)
    } catch (e: IllegalStateException) {
        Log.w(logTag, "Could not promote worker to foreground; continuing in background", e)
    }
}

private val GeneratedTextWrappingQuotes = charArrayOf('"', '\'', '`', '“', '”', '‘', '’')
private val GeneratedTextWhitespace = Regex("\\s+")
internal const val GENERATED_TITLE_MAX_LENGTH = 80
internal const val GENERATED_SUMMARY_MAX_LENGTH = 600

/**
 * Normalizes an LLM-generated title before persisting: collapses newlines/whitespace,
 * strips wrapping quotes/backticks and leading list/heading markers, and caps the length
 * (the title also becomes the Obsidian export filename and the share subject). Returns an
 * empty string when nothing usable remains so callers can treat it as a failed generation.
 */
internal fun sanitizeGeneratedTitle(raw: String): String =
    raw
        .replace(GeneratedTextWhitespace, " ")
        .trim()
        .trim(*GeneratedTextWrappingQuotes)
        .trim()
        .trimStart('#', '-', '*', '•', ' ')
        .trim()
        .take(GENERATED_TITLE_MAX_LENGTH)
        .trim()

/**
 * Normalizes an LLM-generated summary: trims outer whitespace/wrapping quotes and caps the
 * length so a runaway generation never floods the list subline or export frontmatter.
 */
internal fun sanitizeGeneratedSummary(raw: String): String =
    raw
        .trim()
        .trim(*GeneratedTextWrappingQuotes)
        .trim()
        .take(GENERATED_SUMMARY_MAX_LENGTH)
        .trim()

private fun ensureTranscriptionProgressChannel(context: Context) {
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    if (notificationManager.getNotificationChannel(TRANSCRIPTION_FOREGROUND_CHANNEL_ID) != null) {
        return
    }
    val channel =
        NotificationChannel(
            TRANSCRIPTION_FOREGROUND_CHANNEL_ID,
            context.getString(R.string.transcription_progress_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.transcription_progress_channel_description)
            setShowBadge(false)
        }
    notificationManager.createNotificationChannel(channel)
}

private fun ensureEnhancementProgressChannel(context: Context) {
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    if (notificationManager.getNotificationChannel(ENHANCEMENT_FOREGROUND_CHANNEL_ID) != null) {
        return
    }
    val channel =
        NotificationChannel(
            ENHANCEMENT_FOREGROUND_CHANNEL_ID,
            context.getString(R.string.enhancement_progress_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.enhancement_progress_channel_description)
            setShowBadge(false)
        }
    notificationManager.createNotificationChannel(channel)
}

internal const val TRANSCRIPTION_ERROR_CHANNEL_ID = "transcription_errors"
private const val TRANSCRIPTION_ERROR_GROUP = "transcription_error_group"
private const val TRANSCRIPTION_ERROR_SUMMARY_NOTIFICATION_ID = 2003
internal const val TRANSCRIPTION_READY_CHANNEL_ID = "transcription_ready"

internal fun transcriptionErrorLaunchIntent(
    context: Context,
    recordingId: UUID,
) =
    context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
        action = ACTION_OPEN_TRANSCRIPTION_RECORDING
        putExtra(EXTRA_TRANSCRIPTION_RECORDING_ID, recordingId.toString())
    }

/**
 * PIPE-04: terminal-failure notification with a branded small icon, a tap action into
 * the app, and a group summary so a backlog failing on a shared root cause collapses
 * into one stack instead of dozens of loose notifications. Posted for every terminal
 * FAILED path so coverage is consistent; silently no-ops if POST_NOTIFICATIONS was
 * denied (the recording row still surfaces the FAILED state in-app).
 *
 * Top-level (rather than a private worker method) so worker tests can stub the post
 * through the established mockkStatic harness for this file.
 */
internal fun showTranscriptionErrorNotification(
    context: Context,
    recordingId: UUID,
    errorMessage: String,
) {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channelId = TRANSCRIPTION_ERROR_CHANNEL_ID
    if (notificationManager.getNotificationChannel(channelId) == null) {
        val channel = NotificationChannel(
            channelId,
            context.getString(R.string.transcription_error_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        notificationManager.createNotificationChannel(channel)
    }

    val contentIntent =
        transcriptionErrorLaunchIntent(context, recordingId)?.let { launchIntent ->
            PendingIntent.getActivity(
                context,
                recordingId.hashCode(),
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_notif_transcription)
        .setContentTitle(context.getString(R.string.transcription_error_notification_title))
        .setContentText(errorMessage)
        .setStyle(NotificationCompat.BigTextStyle().bigText(errorMessage))
        .setGroup(TRANSCRIPTION_ERROR_GROUP)
        .setAutoCancel(true)
        .apply { contentIntent?.let(::setContentIntent) }
        // RELY-6: the rescue path kept the audio; retry it right here instead of sending the
        // user hunting for the recording inside the app.
        .addAction(
            0,
            context.getString(R.string.transcription_retry),
            transcriptionRetryPendingIntent(context, recordingId),
        )
        .build()
    notificationManager.notify(recordingId.hashCode(), notification)

    val groupSummary = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_notif_transcription)
        .setContentTitle(context.getString(R.string.transcription_error_group_summary))
        .setGroup(TRANSCRIPTION_ERROR_GROUP)
        .setGroupSummary(true)
        .setAutoCancel(true)
        .apply { contentIntent?.let(::setContentIntent) }
        .build()
    notificationManager.notify(TRANSCRIPTION_ERROR_SUMMARY_NOTIFICATION_ID, groupSummary)
}

internal fun showTranscriptionReadyNotification(
    context: Context,
    recordingId: UUID,
    transcript: Transcript? = null,
) {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (notificationManager.getNotificationChannel(TRANSCRIPTION_READY_CHANNEL_ID) == null) {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                TRANSCRIPTION_READY_CHANNEL_ID,
                context.getString(R.string.transcription_ready_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }
    val contentIntent =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launchIntent ->
            launchIntent.action = ACTION_OPEN_TRANSCRIPTION_RECORDING
            launchIntent.putExtra(EXTRA_TRANSCRIPTION_RECORDING_ID, recordingId.toString())
            PendingIntent.getActivity(
                context,
                recordingId.hashCode(),
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    val notification =
        NotificationCompat
            .Builder(context, TRANSCRIPTION_READY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_transcription)
            .setContentTitle(context.getString(R.string.transcription_ready_notification_title))
            .setContentText(
                transcript?.processedText?.takeIf { it.isNotBlank() }
                    ?: transcript?.rawText?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.transcription_ready_notification_body),
            )
            .apply {
                transcript?.let { value ->
                    setStyle(NotificationCompat.BigTextStyle().bigText(terminalTranscriptNotificationText(context, value)))
                    addTranscriptCopyActions(context, recordingId, value)
                }
            }
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(buildPrivateTranscriptionPublicVersion(context))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .apply { contentIntent?.let(::setContentIntent) }
            .build()
    notificationManager.notify(recordingId.hashCode(), notification)
}

internal fun showTranscriptionCleanupRetryNotification(
    context: Context,
    recordingId: UUID,
    transcript: Transcript? = null,
) {
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (notificationManager.getNotificationChannel(TRANSCRIPTION_READY_CHANNEL_ID) == null) {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                TRANSCRIPTION_READY_CHANNEL_ID,
                context.getString(R.string.transcription_ready_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }
    val contentIntent =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launchIntent ->
            launchIntent.action = ACTION_OPEN_TRANSCRIPTION_RECORDING
            launchIntent.putExtra(EXTRA_TRANSCRIPTION_RECORDING_ID, recordingId.toString())
            PendingIntent.getActivity(
                context,
                recordingId.hashCode(),
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    val notification =
        NotificationCompat
            .Builder(context, TRANSCRIPTION_READY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_transcription)
            .setContentTitle(context.getString(R.string.transcription_cleanup_retry_notification_title))
            .setContentText(
                transcript?.rawText?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.transcription_cleanup_retry_notification_body),
            )
            .apply {
                transcript?.let { value ->
                    setStyle(NotificationCompat.BigTextStyle().bigText(terminalTranscriptNotificationText(context, value)))
                    addTranscriptCopyActions(context, recordingId, value)
                }
            }
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(buildPrivateTranscriptionPublicVersion(context))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .apply { contentIntent?.let(::setContentIntent) }
            .build()
    notificationManager.notify(recordingId.hashCode(), notification)
}

internal fun terminalTranscriptNotificationText(
    context: Context,
    transcript: Transcript,
): String {
    val raw = transcript.rawText.trim()
    val processed = transcript.processedText?.trim()?.takeIf { it.isNotEmpty() && it != raw }
    return if (processed == null) {
        raw
    } else {
        context.getString(R.string.transcription_ready_ai_label) + "\n" + processed +
            "\n\n" + context.getString(R.string.transcription_ready_raw_label) + "\n" + raw
    }
}

private fun NotificationCompat.Builder.addTranscriptCopyActions(
    context: Context,
    recordingId: UUID,
    transcript: Transcript,
) {
    if (transcript.rawText.isNotBlank()) {
        addAction(
            0,
            context.getString(R.string.transcription_ready_copy_raw),
            transcriptionCopyPendingIntent(context, recordingId, copyAiResult = false),
        )
    }
    val processedText = transcript.processedText
    if (!processedText.isNullOrBlank() && processedText.trim() != transcript.rawText.trim()) {
        addAction(
            0,
            context.getString(R.string.transcription_ready_copy_ai),
            transcriptionCopyPendingIntent(context, recordingId, copyAiResult = true),
        )
    }
}

private fun transcriptionRetryPendingIntent(
    context: Context,
    recordingId: UUID,
): PendingIntent {
    val intent =
        Intent(context, TranscriptionRetryReceiver::class.java)
            .setAction(TranscriptionRetryReceiver.ACTION_RETRY_TRANSCRIPTION)
            .putExtra(EXTRA_TRANSCRIPTION_RECORDING_ID, recordingId.toString())
    return PendingIntent.getBroadcast(
        context,
        recordingId.hashCode() xor RETRY_REQUEST_CODE_MASK,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

private fun transcriptionCopyPendingIntent(
    context: Context,
    recordingId: UUID,
    copyAiResult: Boolean,
): PendingIntent {
    val action =
        if (copyAiResult) {
            QuickInputCopyActivity.ACTION_COPY_TRANSCRIPT_AI
        } else {
            QuickInputCopyActivity.ACTION_COPY_TRANSCRIPT_RAW
        }
    val requestCode = recordingId.hashCode() xor if (copyAiResult) COPY_AI_REQUEST_CODE_MASK else COPY_RAW_REQUEST_CODE_MASK
    // An activity, not a broadcast: the clipboard write needs window focus to be
    // honored on every build (see QuickInputCopyActivity).
    val intent =
        Intent(context, QuickInputCopyActivity::class.java)
            .setAction(action)
            .putExtra(EXTRA_TRANSCRIPTION_RECORDING_ID, recordingId.toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

private fun buildPrivateTranscriptionPublicVersion(context: Context): Notification =
    NotificationCompat
        .Builder(context, TRANSCRIPTION_READY_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notif_transcription)
        .setContentTitle(context.getString(R.string.transcription_ready_notification_title))
        .setContentText(context.getString(R.string.transcription_ready_notification_body))
        .build()

internal fun buildTranscriptionFailureResult(errorMessage: String): androidx.work.ListenableWorker.Result {
    return androidx.work.ListenableWorker.Result.failure(
        Data.Builder()
            .putString(TranscriptionWorker.OUTPUT_ERROR, errorMessage)
            .build()
    )
}

internal fun buildTranscriptionSuccessResult(transcriptId: UUID): androidx.work.ListenableWorker.Result {
    return androidx.work.ListenableWorker.Result.success(
        Data.Builder()
            .putString(TranscriptionWorker.OUTPUT_TRANSCRIPT_ID, transcriptId.toString())
            .build()
    )
}

internal data class ChunkTranscription(
    val text: String,
    val wordTimings: List<RecognizedWordTiming>? = null,
 )

internal fun mapOutcomeForChunkTranscription(outcome: TranscriptionOutcome): ChunkTranscription {
    return when (outcome) {
        is TranscriptionOutcome.Success -> ChunkTranscription(
            text = outcome.text,
            wordTimings = outcome.wordTimings,
        )
        TranscriptionOutcome.NoSpeech -> ChunkTranscription(text = "")
        is TranscriptionOutcome.ModelUnavailable -> {
            throw NonRetryableTranscriptionException(
                "Speech model unavailable: ${outcome.reason}"
            )
        }
        is TranscriptionOutcome.EngineError -> {
            val message = "Speech engine failed: ${outcome.reason}"
            if (outcome.retryable) {
                throw RetryableTranscriptionException(message)
            } else {
                throw NonRetryableTranscriptionException(message)
            }
        }
    }
}

/**
 * I18N-05: terminal failure notifications show short actionable copy mapped from the failure
 * class; raw exception messages are developer diagnostics and stay in logs / the persisted
 * machine codes.
 */
internal fun transcriptionFailureNotificationText(
    context: Context,
    exception: Exception,
): String = transcriptionFailureNotificationText(context, exception.message)

internal fun transcriptionFailureNotificationText(
    context: Context,
    errorMessage: String?,
): String {
    val message = errorMessage.orEmpty()
    return when {
        dev.chirpboard.app.data.model.isWaitingForSpeechModel(message) ->
            context.getString(R.string.transcription_error_model_missing)

        message.contains("ENOSPC") || message.contains("No space left", ignoreCase = true) ->
            context.getString(R.string.transcription_error_storage_full)

        message.contains("Audio file not found", ignoreCase = true) ->
            context.getString(R.string.transcription_error_audio_missing)

        message.contains("Out of memory", ignoreCase = true) ->
            context.getString(R.string.transcription_error_out_of_memory)

        message.contains("Daily cloud transcription limit", ignoreCase = true) ->
            context.getString(R.string.transcription_error_cloud_daily_limit)

        message.contains("cloud", ignoreCase = true) ->
            context.getString(R.string.transcription_error_cloud)

        else -> context.getString(R.string.transcription_error_generic)
    }
}

/**
 * A cloud recording falls back to the local engine when the cloud request could never
 * succeed: the payload exceeds the service limits, or the endpoint/auth is not configured
 * at all (the routing preference can outlive a working configuration).
 * TEMPORARILY_UNAVAILABLE stays on the cloud path — that request fails retryable and
 * succeeds once the token service recovers.
 *
 * @return the reliability-log reason code for the reroute, or null to stay on cloud.
 */
internal suspend fun resolveCloudLocalFallbackReason(
    durationMs: Long,
    audioBytes: Long,
    configurationStatus: suspend () -> CloudTranscriptionConfigurationStatus,
): String? =
    when {
        durationMs > GOOGLE_CLOUD_CHIRP_3_MAX_DURATION_MS || audioBytes > GOOGLE_CLOUD_CHIRP_3_MAX_AUDIO_BYTES ->
            "cloud_limit_local_fallback"

        else ->
            when (configurationStatus()) {
                CloudTranscriptionConfigurationStatus.ENDPOINT_MISSING,
                CloudTranscriptionConfigurationStatus.AUTHENTICATION_MISSING,
                -> "cloud_unconfigured_local_fallback"

                CloudTranscriptionConfigurationStatus.READY,
                CloudTranscriptionConfigurationStatus.TEMPORARILY_UNAVAILABLE,
                -> null
            }
    }

internal data class WorkerFailureDisposition(
    val status: RecordingStatus,
    val retry: Boolean
)

internal fun resolveWorkerFailureDisposition(
    exception: Exception,
    runAttemptCount: Int,
    maxRetryCount: Int
): WorkerFailureDisposition {
    val retry = exception is java.io.IOException && runAttemptCount < maxRetryCount
    val status = if (retry) RecordingStatus.PENDING_TRANSCRIPTION else RecordingStatus.FAILED
    return WorkerFailureDisposition(status = status, retry = retry)
}

internal class RetryableTranscriptionException(message: String) : java.io.IOException(message)
internal open class NonRetryableTranscriptionException(message: String) : Exception(message)

internal class ActiveRecordingWaitTimeoutException(
    message: String,
) : NonRetryableTranscriptionException(message)

internal fun computeActiveWaitTimeoutMs(recordingDurationMs: Long?): Long {
    if (recordingDurationMs == null) {
        return TRANSCRIPTION_MAX_ACTIVE_WAIT_MS
    }
    if (recordingDurationMs <= 0L) {
        return TRANSCRIPTION_MIN_ACTIVE_WAIT_MS
    }
    val durationBased =
        TRANSCRIPTION_MIN_ACTIVE_WAIT_MS +
            (recordingDurationMs / 60_000L) * TRANSCRIPTION_ACTIVE_WAIT_PER_MINUTE_MS
    return durationBased.coerceIn(TRANSCRIPTION_MIN_ACTIVE_WAIT_MS, TRANSCRIPTION_MAX_ACTIVE_WAIT_MS)
}

internal suspend fun awaitRecordingInactive(
    recordingState: StateFlow<RecordingState>,
    timeoutMs: Long,
) {
    withTimeoutOrNull(timeoutMs) {
        recordingState.first { !it.isActive }
    } ?: throw ActiveRecordingWaitTimeoutException(
        "Timed out after ${timeoutMs}ms waiting for active recording to finish",
    )
}
