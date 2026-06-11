package dev.chirpboard.app.core.recording

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Origin-aware stop entry point for any surface that needs to end the active recording.
 */
object RecordingActiveStopCommands {
    suspend fun stopActiveRecording(
        context: Context,
        recordingStateManager: RecordingStateManager,
        keyboardStopBridge: KeyboardRecordingStopBridge,
        pendingStopStore: KeyboardPendingStopStore,
        requesterOrigin: RecordingOrigin,
        onKeyboardStopQueued: (() -> Unit)? = null,
    ) {
        val state = recordingStateManager.state.value
        if (!state.isActive) {
            return
        }

        when (state.activeOrigin) {
            RecordingOrigin.KEYBOARD -> {
                if (keyboardStopBridge.requestStop()) {
                    return
                }
                withContext(Dispatchers.IO) {
                    pendingStopStore.enqueue(requesterOrigin)
                }
                onKeyboardStopQueued?.invoke()
            }
            else -> {
                val dispatched = RecordingServiceCommands.stopRecording(context)
                if (!dispatched) {
                    // Mirror the start path: a stop the system refuses to deliver must not
                    // die silently. With a genuinely live capture the app holds a foreground
                    // service so dispatch cannot be refused; a refusal therefore means stale
                    // active state, and surfacing the error both informs the caller's UI and
                    // unsticks that state.
                    recordingStateManager.onRecordingError("Could not stop the recording service")
                }
            }
        }
    }
}
