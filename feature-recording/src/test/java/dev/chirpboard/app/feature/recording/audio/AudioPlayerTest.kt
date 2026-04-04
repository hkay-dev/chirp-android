package dev.chirpboard.app.feature.recording.audio

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioPlayerTest {

    private lateinit var context: Context
    private lateinit var player: AudioPlayer

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        player = AudioPlayer(context)
    }

    @Test
    fun `initial state is Idle`() {
        assertTrue(player.state.value is PlaybackState.Idle)
    }
}
