package dev.chirpboard.app.di

import android.content.Context
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SherpaRecognizerProviderTest {

    @Test
    fun `isReady is false initially`() {
        val mockContext: Context = mockk(relaxed = true)
        val provider = SherpaRecognizerProvider(mockContext)
        assertFalse(provider.isReady())
    }

    @Test
    fun `transcribe returns ModelUnavailable when recognizer not initialized`() = runTest {
        val mockContext: Context = mockk(relaxed = true)
        val provider = SherpaRecognizerProvider(mockContext)
        val outcome = provider.transcribe(FloatArray(10), 16000)
        assertTrue(outcome is TranscriptionOutcome.ModelUnavailable)
        assertEquals("Recognizer is not initialized", (outcome as TranscriptionOutcome.ModelUnavailable).reason)
    }
}
