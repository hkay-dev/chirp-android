package dev.chirpboard.app.feature.transcription

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ForegroundInfo
import dev.chirpboard.app.core.transcription.RecognizedWordTiming
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.data.model.RecordingStatus
import java.util.UUID

internal const val TRANSCRIPTION_MAX_RETRY_COUNT = 3
internal const val TRANSCRIPTION_FOREGROUND_NOTIFICATION_ID = 2001
internal const val TRANSCRIPTION_FOREGROUND_CHANNEL_ID = "transcription_progress"

internal fun transcriptionProgressNotificationTitle(): String = "Transcribing recording"

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
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ForegroundInfo(
            TRANSCRIPTION_FOREGROUND_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    } else {
        ForegroundInfo(TRANSCRIPTION_FOREGROUND_NOTIFICATION_ID, notification)
    }
}

private fun ensureTranscriptionProgressChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        return
    }
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
internal class NonRetryableTranscriptionException(message: String) : Exception(message)
