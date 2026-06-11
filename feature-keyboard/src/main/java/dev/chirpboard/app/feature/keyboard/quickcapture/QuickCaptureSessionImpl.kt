package dev.chirpboard.app.feature.keyboard.quickcapture

import android.content.Context
import android.widget.Toast
import dev.chirpboard.app.core.audio.AudioFocusManager
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.recorder.RecordingError
import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
import dev.chirpboard.app.core.transcription.InlineAudioSource
import dev.chirpboard.app.core.quickcapture.QuickCaptureError
import dev.chirpboard.app.core.quickcapture.QuickCaptureSession
import dev.chirpboard.app.core.quickcapture.QuickCaptureStartResult
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingPermissionGuard
import dev.chirpboard.app.core.recording.RecordingStartResult
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.recording.WaveformBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

class QuickCaptureSessionImpl(
    private val context: Context,
    scope: CoroutineScope,
    inputDeviceSelector: AudioInputDeviceSelector,
    private val recordingStateManager: RecordingStateManager,
    private val audioFocusManager: AudioFocusManager,
) : QuickCaptureSession {
    private val recorder =
        VoiceRecorder(
            context = context,
            coroutineScope = scope,
            inputDeviceSelector = inputDeviceSelector,
            captureStorageMode = VoiceRecorder.CaptureStorageMode.FileBacked,
        )

    override val waveformBuffer: WaveformBuffer get() = recorder.waveformBuffer
    override val sampleCountFlow: StateFlow<Long> get() = recorder.sampleCountFlow

    override var gainMultiplier: Float
        get() = recorder.gainMultiplier
        set(value) {
            recorder.gainMultiplier = value
        }

    override var onRecordingError: ((QuickCaptureError) -> Unit)? = null
        set(value) {
            field = value
            recorder.onRecordingError =
                value?.let { handler ->
                    { error: RecordingError -> handler(QuickCaptureError(error.userMessage)) }
                }
        }

    override var onLimitReached: (() -> Unit)?
        get() = recorder.onLimitReached
        set(value) {
            recorder.onLimitReached = value
        }

    override suspend fun start(): QuickCaptureStartResult {
        if (!RecordingPermissionGuard.hasRecordAudioPermission(context)) {
            return QuickCaptureStartResult.PermissionDenied(
                RecordingPermissionGuard.PERMISSION_DENIED_MESSAGE,
            )
        }

        when (val result = recordingStateManager.tryStartRecording(RecordingOrigin.KEYBOARD)) {
            is RecordingStartResult.Success -> Unit
            is RecordingStartResult.AlreadyRecording -> {
                val sourceLabel =
                    when (result.currentOrigin) {
                        RecordingOrigin.APP -> "app"
                        RecordingOrigin.WIDGET -> "widget"
                        RecordingOrigin.KEYBOARD -> "keyboard"
                    }
                Toast.makeText(context, "Microphone in use by $sourceLabel", Toast.LENGTH_SHORT).show()
                return QuickCaptureStartResult.AlreadyRecording(sourceLabel)
            }
        }

        when (audioFocusManager.requestFocus()) {
            is AudioFocusManager.FocusResult.Denied -> {
                val message = "Another app is using audio"
                recordingStateManager.onRecordingError(message)
                return QuickCaptureStartResult.AudioFocusDenied(message)
            }
            is AudioFocusManager.FocusResult.Granted -> Unit
            else -> Unit
        }

        if (!recorder.start()) {
            audioFocusManager.abandonFocus()
            recordingStateManager.onRecordingError("Failed to start recording")
            return QuickCaptureStartResult.Failed("Failed to start recording")
        }

        recordingStateManager.onRecordingStarted("keyboard_temp_recording")
        return QuickCaptureStartResult.Success
    }

    override suspend fun collectSamples() {
        recorder.collectSamples()
    }

    override fun stop(): FloatArray = recorder.stop()

    fun stopAsAudioSource(): InlineAudioSource? =
        recorder.stopToFileBacked()?.let { capture ->
            InlineAudioSource.PcmFloatFile(
                path = capture.file.absolutePath,
                sampleCount = capture.sampleCount.toLong(),
                sampleRate = capture.sampleRate,
            )
        }

    fun cancelCapture() {
        recorder.cancelCapture()
    }

    override fun close() {
        audioFocusManager.abandonFocus()
        recorder.close()
    }

    fun abandonAudioFocus() {
        audioFocusManager.abandonFocus()
    }
}
