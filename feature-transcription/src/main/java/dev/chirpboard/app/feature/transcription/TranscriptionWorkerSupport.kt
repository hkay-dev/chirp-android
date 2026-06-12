package dev.chirpboard.app.feature.transcription

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.transcription.RecognizedWordTiming
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.data.model.RecordingStatus
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
): String {
    val message = exception.message.orEmpty()
    return when {
        dev.chirpboard.app.data.model.isWaitingForSpeechModel(message) ->
            context.getString(R.string.transcription_error_model_missing)

        message.contains("ENOSPC") || message.contains("No space left", ignoreCase = true) ->
            context.getString(R.string.transcription_error_storage_full)

        else -> context.getString(R.string.transcription_error_generic)
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
