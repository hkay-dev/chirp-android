package dev.chirpboard.app.core.quickcapture

import dev.chirpboard.app.core.recording.WaveformBuffer
import kotlinx.coroutines.flow.StateFlow

/**
 * Short-form PCM capture for dictation surfaces (keyboard IME, voice dialog).
 */
interface QuickCaptureSession {
    val waveformBuffer: WaveformBuffer
    val sampleCountFlow: StateFlow<Long>
    var gainMultiplier: Float

    var onRecordingError: ((QuickCaptureError) -> Unit)?
    var onLimitReached: (() -> Unit)?

    suspend fun start(): QuickCaptureStartResult
    suspend fun collectSamples()
    fun stop(): FloatArray
    fun close()
}

sealed interface QuickCaptureStartResult {
    data object Success : QuickCaptureStartResult

    data class PermissionDenied(val message: String) : QuickCaptureStartResult

    data class AudioFocusDenied(val message: String) : QuickCaptureStartResult

    data class AlreadyRecording(val sourceLabel: String) : QuickCaptureStartResult

    data class Failed(val message: String) : QuickCaptureStartResult
}

data class QuickCaptureError(
    val userMessage: String,
)
