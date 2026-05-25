package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.RecordingOutputFormat

object GaplessSegmentCaptureFactory {
    fun create(
        format: RecordingOutputFormat,
        inputDeviceSelector: AudioInputDeviceSelector,
        sampleRate: Int,
        bitRate: Int,
    ): GaplessSegmentCaptureEngine =
        when (format) {
            RecordingOutputFormat.M4A ->
                GaplessAacSegmentCapture(
                    inputDeviceSelector = inputDeviceSelector,
                    sampleRate = sampleRate,
                    bitRate = bitRate,
                )

            RecordingOutputFormat.WAV ->
                GaplessWavSegmentCapture(
                    inputDeviceSelector = inputDeviceSelector,
                    sampleRate = sampleRate,
                )

            RecordingOutputFormat.MP3 ->
                GaplessMp3SegmentCapture(
                    inputDeviceSelector = inputDeviceSelector,
                    sampleRate = sampleRate,
                    bitRate = bitRate,
                )
        }
}

typealias GaplessSegmentCapture = GaplessAacSegmentCapture
