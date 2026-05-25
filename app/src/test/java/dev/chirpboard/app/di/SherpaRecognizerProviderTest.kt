package dev.chirpboard.app.di

import android.content.Context
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.download.ModelDownloader
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SherpaRecognizerProviderTest {

    private fun createProvider(): SherpaRecognizerProvider {
        val mockContext: Context = mockk(relaxed = true)
        val downloader = mockk<ModelDownloader>()
        every { downloader.isModelDownloaded() } returns false
        return SherpaRecognizerProvider(mockContext, downloader)
    }

    @Test
    fun `isReady is false initially`() {
        val provider = createProvider()
        assertFalse(provider.isReady())
    }

    @Test
    fun `transcribe returns ModelUnavailable when recognizer not initialized`() = runTest {
        val provider = createProvider()
        val outcome = provider.transcribe(FloatArray(10), 16000)
        assertTrue(outcome is TranscriptionOutcome.ModelUnavailable)
        assertEquals("Recognizer is not initialized", (outcome as TranscriptionOutcome.ModelUnavailable).reason)
    }
}
