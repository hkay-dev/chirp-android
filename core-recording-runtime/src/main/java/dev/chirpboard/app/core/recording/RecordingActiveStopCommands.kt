package dev.chirpboard.app.core.recording

import android.content.Context

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
        if (state is RecordingState.Stopping) {
            // The session is already finishing. Re-dispatching a service stop is a no-op,
            // but the keyboard arm below would enqueue a pending stop that survives this
            // session's cleanup and can kill the NEXT dictation the moment it starts.
            return
        }

        when (state.activeOrigin) {
            RecordingOrigin.KEYBOARD -> {
                if (keyboardStopBridge.requestStop()) {
                    return
                }
                if (pendingStopStore.enqueue(requesterOrigin)) {
                    onKeyboardStopQueued?.invoke()
                }
            }
            RecordingOrigin.RECOGNITION -> {
                // Recognition captures are driven in-process by the RECOGNIZE_SPEECH
                // dialog/service, not by RecordingService. Dispatching a service stop
                // would start an unrelated service instance for a session it never
                // owned, and the refusal fallback below would force Error and release
                // the lock while the recognition recorder is still capturing. The
                // recognition surface ends its own session.
                return
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
