package dev.chirpboard.app.feature.recording.audio

import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioPlayerTest {

    private lateinit var player: AudioPlayer

    @Before
    fun setup() {
        player = AudioPlayer()
    }

    @Test
    fun `initial state is Idle`() {
        assertTrue(player.state.value is PlaybackState.Idle)
    }
}
