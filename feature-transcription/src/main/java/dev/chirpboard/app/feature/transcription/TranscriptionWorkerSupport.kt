package dev.chirpboard.app.feature.transcription

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
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

internal fun transcriptionProgressNotificationTitle(): String = "Transcribing recording"
internal fun enhancementProgressNotificationTitle(): String = "Enhancing recording"
internal fun backgroundWorkerForegroundServiceType(): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

internal fun buildTranscriptionProgressNotification(context: Context): Notification {
    ensureTranscriptionProgressChannel(context)
    return NotificationCompat
        .Builder(context, TRANSCRIPTION_FOREGROUND_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(transcriptionProgressNotificationTitle())
        .setContentText("Processing audio in the background")
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
        .setContentTitle(enhancementProgressNotificationTitle())
        .setContentText("Applying recording enhancements in the background")
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

private fun ensureTranscriptionProgressChannel(context: Context) {
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    if (notificationManager.getNotificationChannel(TRANSCRIPTION_FOREGROUND_CHANNEL_ID) != null) {
        return
    }
    val channel =
        NotificationChannel(
            TRANSCRIPTION_FOREGROUND_CHANNEL_ID,
            "Transcription",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows while a recording is being transcribed"
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
            "Enhancement",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows while a recording is being enhanced"
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
