package dev.chirpboard.app

import android.content.Context
import io.mockk.mockk
import dev.chirpboard.app.feature.keyboard.recorder.VoiceRecorder
import org.junit.Assert.assertNotNull
import org.junit.Test

class VoiceRecorderTest {
    @Test
    fun `test recorder initialization`() {
        val context = mockk<Context>(relaxed = true)
        val recorder = VoiceRecorder(context)
        assertNotNull(recorder)
    }
}
