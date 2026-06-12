package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.audio.AudioInputDeviceSelector

/**
 * Builds the live segment-capture engine. Live segments are always crash-tolerant WAV
 * (header + PCM); the user's M4A/MP3 output preference is honored later by
 * `RecordingSegmentConcatenator` re-encoding at finalize, not by the capture engine. The
 * earlier per-format AAC/MP3 engines were unreachable in production and were removed.
 */
object GaplessSegmentCaptureFactory {
    fun create(
        inputDeviceSelector: AudioInputDeviceSelector,
        sampleRate: Int,
    ): GaplessSegmentCaptureEngine =
        GaplessWavSegmentCapture(
            inputDeviceSelector = inputDeviceSelector,
            sampleRate = sampleRate,
        )
}
