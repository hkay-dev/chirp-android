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
    /**
     * User-facing label other surfaces show when this gate holds the mic. The
     * RECOGNIZE_SPEECH dialog/service is not the keyboard IME, so it reports
     * "voice recognition" rather than the misleading "keyboard" the shared
     * [RecordingOrigin.KEYBOARD] maps to. (Telemetry origin remains KEYBOARD until a
     * dedicated RECOGNITION origin is added to the shared contract.)
     */
    private val sourceLabel: String = RECOGNITION_SOURCE_LABEL,
) {
    private var held = false

    @Synchronized
    fun tryAcquire(): VoiceRecognitionCaptureGateResult {
        if (held) {
            // Non-reentrant: a second session must never believe it owns the
            // microphone while an earlier session still holds the gate.
            return VoiceRecognitionCaptureGateResult.Busy(sourceLabel)
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

    private companion object {
        const val RECOGNITION_SOURCE_LABEL = "voice recognition"
    }
}

private fun RecordingOrigin.sourceLabel(): String =
    when (this) {
        RecordingOrigin.APP -> "app"
        RecordingOrigin.KEYBOARD -> "keyboard"
        RecordingOrigin.WIDGET -> "widget"
    }
