package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test

class GaplessSegmentCaptureFactoryTest {
    private val inputDeviceSelector = mockk<AudioInputDeviceSelector>(relaxed = true)

    @Test
    fun create_returnsGaplessWavSegmentCapture() {
        val engine =
            GaplessSegmentCaptureFactory.create(
                inputDeviceSelector = inputDeviceSelector,
                sampleRate = 32_000,
            )

        assertTrue(engine is GaplessWavSegmentCapture)
    }
}
