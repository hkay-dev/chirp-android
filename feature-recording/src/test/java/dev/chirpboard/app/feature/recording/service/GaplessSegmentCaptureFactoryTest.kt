package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.RecordingOutputFormat
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test

class GaplessSegmentCaptureFactoryTest {
    private val inputDeviceSelector = mockk<AudioInputDeviceSelector>(relaxed = true)

    @Test
    fun create_m4a_returnsGaplessAacSegmentCapture() {
        val engine =
            GaplessSegmentCaptureFactory.create(
                format = RecordingOutputFormat.M4A,
                inputDeviceSelector = inputDeviceSelector,
                sampleRate = 44_100,
                bitRate = 128_000,
            )

        assertTrue(engine is GaplessAacSegmentCapture)
    }

    @Test
    fun create_wav_returnsGaplessWavSegmentCapture() {
        val engine =
            GaplessSegmentCaptureFactory.create(
                format = RecordingOutputFormat.WAV,
                inputDeviceSelector = inputDeviceSelector,
                sampleRate = 32_000,
                bitRate = 96_000,
            )

        assertTrue(engine is GaplessWavSegmentCapture)
    }

    @Test
    fun create_mp3_returnsGaplessMp3SegmentCapture() {
        val engine =
            GaplessSegmentCaptureFactory.create(
                format = RecordingOutputFormat.MP3,
                inputDeviceSelector = inputDeviceSelector,
                sampleRate = 32_000,
                bitRate = 96_000,
            )

        assertTrue(engine is GaplessMp3SegmentCapture)
    }
}
