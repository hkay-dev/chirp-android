package dev.chirpboard.app.core.recording

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Origin-aware stop entry point for any surface that needs to end the active recording.
 */
object RecordingActiveStopCommands {
    fun stopActiveRecording(
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
                CoroutineScope(Dispatchers.IO).launch {
                    pendingStopStore.enqueue(requesterOrigin)
                }
                onKeyboardStopQueued?.invoke()
            }
            else -> RecordingServiceCommands.stopRecording(context)
        }
    }
}
