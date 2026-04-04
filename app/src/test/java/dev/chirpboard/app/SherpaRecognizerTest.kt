package dev.chirpboard.app

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class SherpaRecognizerTest {
    @Test
    fun `test initialization`() {
        val context = mockk<Context>(relaxed = true)
        val recognizer = SherpaRecognizer(context)
        assertNotNull(recognizer)
    }
}
