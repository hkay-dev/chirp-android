package dev.chirpboard.app.feature.transcription

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun terminalRecordingNotificationsEnabled(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

internal fun terminalRecordingChannelEnabled(
    context: Context,
    channelId: String,
): Boolean {
    val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return false
    val channel = notificationManager.getNotificationChannel(channelId) ?: return true
    return channel.importance != NotificationManager.IMPORTANCE_NONE
}

/**
 * Posts terminal keyboard-dictation notifications from a Room-backed pending marker. The marker
 * clears only after NotificationManager accepts the post, so process death between the terminal
 * database commit and notification delivery is replayed on the next app start. Notification ids
 * are recording-stable, which makes a crash after posting but before clearing an idempotent replay.
 */
@Singleton
class TerminalRecordingNotificationDelivery
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val recordingRepository: RecordingRepository,
    ) {
        private val deliveryMutex = Mutex()

        suspend fun deliverRequested(recordingId: UUID): Boolean =
            deliveryMutex.withLock {
                val recording = recordingRepository.getRecording(recordingId) ?: return@withLock false
                deliver(recording)
            }

        suspend fun recoverPendingNotifications(): Int =
            deliveryMutex.withLock {
                var delivered = 0
                recordingRepository.getPendingTerminalNotifications().forEach { recording ->
                    if (deliver(recording)) {
                        delivered += 1
                    }
                }
                delivered
            }

        private suspend fun deliver(recording: Recording): Boolean {
            if (!recording.terminalNotificationPending || recording.status !in TERMINAL_STATUSES) {
                return false
            }
            if (!terminalRecordingNotificationsEnabled(context)) {
                return false
            }

            val transcript: Transcript? = recordingRepository.getTranscript(recording.id)
            val hasTranscript = transcript != null
            val channelId =
                if (recording.status == RecordingStatus.COMPLETED || hasTranscript) {
                    TRANSCRIPTION_READY_CHANNEL_ID
                } else {
                    TRANSCRIPTION_ERROR_CHANNEL_ID
                }
            if (!terminalRecordingChannelEnabled(context, channelId)) {
                return false
            }
            try {
                when {
                    recording.status == RecordingStatus.COMPLETED ->
                        showTranscriptionReadyNotification(context, recording.id, transcript)

                    hasTranscript ->
                        showTranscriptionCleanupRetryNotification(context, recording.id, transcript)

                    else ->
                        showTranscriptionErrorNotification(
                            context,
                            recording.id,
                            transcriptionFailureNotificationText(context, recording.errorMessage),
                        )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not post terminal recording notification for ${recording.id}", e)
                return false
            }

            return try {
                recordingRepository.clearPendingTerminalNotification(recording.id, recording.status)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not clear terminal notification marker for ${recording.id}", e)
                false
            }
        }

        private companion object {
            private const val TAG = "TerminalNotification"
            private val TERMINAL_STATUSES = setOf(RecordingStatus.COMPLETED, RecordingStatus.FAILED)
        }
    }
