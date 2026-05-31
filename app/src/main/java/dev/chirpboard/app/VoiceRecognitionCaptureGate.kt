package dev.chirpboard.app

import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingStartResult
import dev.chirpboard.app.core.recording.RecordingStateManager

internal sealed class VoiceRecognitionCaptureGateResult {
    data object Acquired : VoiceRecognitionCaptureGateResult()

    data class Busy(val sourceLabel: String) : VoiceRecognitionCaptureGateResult()
}

internal class VoiceRecognitionCaptureGate(
    private val recordingStateManager: RecordingStateManager,
    private val origin: RecordingOrigin = RecordingOrigin.KEYBOARD,
) {
    private var held = false

    @Synchronized
    fun tryAcquire(): VoiceRecognitionCaptureGateResult {
        if (held) {
            return VoiceRecognitionCaptureGateResult.Acquired
        }

        return when (val result = recordingStateManager.tryStartRecording(origin)) {
            RecordingStartResult.Success -> {
                held = true
                VoiceRecognitionCaptureGateResult.Acquired
            }
            is RecordingStartResult.AlreadyRecording ->
                VoiceRecognitionCaptureGateResult.Busy(result.currentOrigin.sourceLabel())
        }
    }

    @Synchronized
    fun onRecorderStarted(audioPathLabel: String) {
        if (held) {
            recordingStateManager.onRecordingStarted(audioPathLabel)
        }
    }

    @Synchronized
    fun releaseCompleted() {
        if (!held) {
            return
        }
        held = false
        recordingStateManager.transitionToStopping()
        recordingStateManager.onRecordingCompleted()
    }

    @Synchronized
    fun releaseError(
        message: String,
        cause: Throwable? = null,
    ) {
        if (!held) {
            return
        }
        held = false
        recordingStateManager.onRecordingError(message, cause)
    }

    @Synchronized
    fun isHeld(): Boolean = held
}

private fun RecordingOrigin.sourceLabel(): String =
    when (this) {
        RecordingOrigin.APP -> "app"
        RecordingOrigin.KEYBOARD -> "keyboard"
        RecordingOrigin.WIDGET -> "widget"
    }
