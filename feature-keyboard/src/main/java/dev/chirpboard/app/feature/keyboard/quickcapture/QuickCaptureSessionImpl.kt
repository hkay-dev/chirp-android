package dev.chirpboard.app.feature.keyboard.quickcapture

import android.content.Context
import android.widget.Toast
import dev.chirpboard.app.core.audio.AudioFocusManager
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.DeviceLostEvent
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
import dev.chirpboard.app.feature.keyboard.R
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class QuickCaptureSessionImpl(
    private val context: Context,
    scope: CoroutineScope,
    private val inputDeviceSelector: AudioInputDeviceSelector,
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

    /**
     * AUD-02 (keyboard half): sustained digital-silence transitions from the recorder —
     * pure zeros mean the platform silenced this client (another app holds the mic or the
     * privacy toggle is off) while reads keep succeeding. Display-only: drives the "no
     * audio detected" hint in the keyboard panel, never stops or commits the dictation.
     */
    var onSilenceStateChanged: ((Boolean) -> Unit)?
        get() = recorder.onSilenceStateChanged
        set(value) {
            recorder.onSilenceStateChanged = value
        }

    /**
     * MIC-014 (keyboard half): hot-unplug events for the ACTIVE capture device, straight
     * from the shared selector so this surface reacts to the same physical event as the
     * recording service. Display-only here — the coordinator surfaces a transient hint
     * while the platform reroutes capture to a fallback mic; nothing stops the dictation.
     */
    val deviceLostEvents: SharedFlow<DeviceLostEvent> get() = inputDeviceSelector.deviceLostEvents

    override suspend fun start(): QuickCaptureStartResult = start(captureFilePath = null)

    suspend fun start(captureFilePath: String?): QuickCaptureStartResult {
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
                        RecordingOrigin.APP -> context.getString(R.string.keyboard_mic_source_app)
                        RecordingOrigin.WIDGET -> context.getString(R.string.keyboard_mic_source_widget)
                        RecordingOrigin.KEYBOARD -> context.getString(R.string.keyboard_mic_source_keyboard)
                        RecordingOrigin.RECOGNITION -> context.getString(R.string.keyboard_mic_source_recognition)
                    }
                // MIC-008: a KEYBOARD-origin busy result means our own previous dictation's
                // stop pipeline still holds the global lock — to the user "the keyboard" is
                // themselves, so the self-referential "mic in use by keyboard" toast is
                // suppressed; the coordinator owns that window's UI (Transcribing phase).
                // A genuinely other-origin busy keeps the explanatory toast.
                if (result.currentOrigin != RecordingOrigin.KEYBOARD) {
                    Toast
                        .makeText(
                            context,
                            context.getString(R.string.keyboard_mic_in_use, sourceLabel),
                            Toast.LENGTH_SHORT,
                        ).show()
                }
                return QuickCaptureStartResult.AlreadyRecording(sourceLabel)
            }
        }

        when (audioFocusManager.requestFocus()) {
            is AudioFocusManager.FocusResult.Denied -> {
                val message = context.getString(R.string.keyboard_audio_busy)
                recordingStateManager.onRecordingError(message)
                return QuickCaptureStartResult.AudioFocusDenied(message)
            }
            is AudioFocusManager.FocusResult.Granted -> Unit
            else -> Unit
        }

        if (!recorder.start(captureFilePath?.let(::File), collectImmediately = true)) {
            audioFocusManager.abandonFocus()
            val message = context.getString(R.string.keyboard_record_start_failed)
            recordingStateManager.onRecordingError(message)
            return QuickCaptureStartResult.Failed(message)
        }

        recordingStateManager.onRecordingStarted("keyboard_temp_recording")
        return QuickCaptureStartResult.Success
    }

    suspend fun awaitFirstSamples(): Boolean = recorder.awaitFirstSamples()

    fun activeFileBackedSnapshot(): VoiceRecorder.CapturedPcmFloatFile? =
        recorder.activeFileBackedSnapshot()

    fun latestIntegrityReport(): VoiceRecorder.CaptureIntegrityReport? =
        recorder.latestIntegrityReport()

    override suspend fun collectSamples() {
        recorder.collectSamples()
    }

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
