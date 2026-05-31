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
            else -> RecordingServiceCommands.stopRecording(context)
        }
    }
}
