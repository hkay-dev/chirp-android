package dev.chirpboard.app.core.audio

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPlaybackControllerTest {
    @Test
    fun initialState_isIdle() {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        val controller = RecordingPlaybackController(context)
        assertTrue(controller.state.value.isIdle)
    }
}
