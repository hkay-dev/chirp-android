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
        val coroutineScope = kotlinx.coroutines.test.TestScope()
        val recorder = VoiceRecorder(context, coroutineScope)
        assertNotNull(recorder)
    }
}
