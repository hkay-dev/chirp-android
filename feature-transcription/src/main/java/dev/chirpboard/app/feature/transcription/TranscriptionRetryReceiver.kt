package dev.chirpboard.app.feature.transcription

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.chirpboard.app.core.transcription.EXTRA_TRANSCRIPTION_RECORDING_ID
import dev.chirpboard.app.core.transcription.ManualRecoveryResult
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.core.transcription.toUserMessage
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "TranscriptionRetry"

/**
 * RELY-6: the "Retry" action on a failed-transcription notification. The rescue path keeps
 * the audio of every failed dictation, but until now the only retry affordance lived inside
 * the recordings library — exactly where a user coming from a notification is not. This runs
 * the same manual recovery the in-app Retry uses, straight from the failure surface.
 *
 * A receiver (not the copy activity): the retry needs no window focus, only a bounded
 * database claim plus a WorkManager enqueue inside the goAsync window.
 */
class TranscriptionRetryReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface RetryEntryPoint {
        fun transcriptionRecovery(): TranscriptionRecovery
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_RETRY_TRANSCRIPTION) return
        val recordingId =
            intent.getStringExtra(EXTRA_TRANSCRIPTION_RECORDING_ID)
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return
        val recovery =
            EntryPointAccessors
                .fromApplication(context.applicationContext, RetryEntryPoint::class.java)
                .transcriptionRecovery()
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val result =
                    runCatching { recovery.retry(recordingId) }
                        .onFailure { Log.e(TAG, "Notification retry failed for $recordingId", it) }
                        .getOrNull()
                withContext(Dispatchers.Main) {
                    if (result == ManualRecoveryResult.ENQUEUED) {
                        // The stable per-recording id: the failure notification this action
                        // came from. A later terminal state re-posts under the same id.
                        context
                            .getSystemService(NotificationManager::class.java)
                            ?.cancel(recordingId.hashCode())
                    }
                    val message =
                        result?.toUserMessage(
                            context,
                            success = context.getString(R.string.transcription_retry_enqueued),
                        ) ?: context.getString(R.string.transcription_retry_failed)
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        internal const val ACTION_RETRY_TRANSCRIPTION = "dev.chirpboard.app.action.RETRY_TRANSCRIPTION"
    }
}
