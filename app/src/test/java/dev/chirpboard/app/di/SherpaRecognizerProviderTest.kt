package dev.chirpboard.app.di

import android.content.Context
import dev.chirpboard.app.RecognizerManager
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.download.ModelDownloader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SherpaRecognizerProviderTest {

    @After
    fun teardown() {
        unmockkAll()
        RecognizerManager.resetUsageStateForTest()
    }

    private fun createProvider(modelDownloaded: Boolean = false): SherpaRecognizerProvider {
        val mockContext: Context = mockk(relaxed = true)
        val downloader = mockk<ModelDownloader>()
        every { downloader.isModelDownloaded() } returns modelDownloaded
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

    @Test
    fun `transcribe attempts a re-warm when the model files are present`() = runTest {
        // PRF-2 defense in depth: after a pressure release, a surface that goes straight to
        // transcribe must re-warm the shared recognizer rather than fail the dictation.
        mockkObject(RecognizerManager)
        every { RecognizerManager.peekReadyRecognizer() } returns null
        coEvery { RecognizerManager.initializeRecognizer(any()) } returns false

        val provider = createProvider(modelDownloaded = true)
        val outcome = provider.transcribe(FloatArray(10), 16000)

        coVerify(exactly = 1) { RecognizerManager.initializeRecognizer(any()) }
        // The re-warm itself failed here, so the outcome still degrades safely.
        assertTrue(outcome is TranscriptionOutcome.ModelUnavailable)
    }

    @Test
    fun `transcribe does not attempt a re-warm when the model is not downloaded`() = runTest {
        mockkObject(RecognizerManager)
        every { RecognizerManager.peekReadyRecognizer() } returns null
        coEvery { RecognizerManager.initializeRecognizer(any()) } returns true

        val provider = createProvider(modelDownloaded = false)
        val outcome = provider.transcribe(FloatArray(10), 16000)

        coVerify(exactly = 0) { RecognizerManager.initializeRecognizer(any()) }
        assertTrue(outcome is TranscriptionOutcome.ModelUnavailable)
    }
}
