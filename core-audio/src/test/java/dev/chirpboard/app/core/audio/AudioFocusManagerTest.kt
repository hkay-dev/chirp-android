package dev.chirpboard.app.core.audio

import android.media.AudioManager
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AudioFocusManagerTest {
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `focus loss after abandon is ignored`() {
        val focusManager = AudioFocusManager(mockk(relaxed = true))
        focusManager.markFocusOwnedForTest()
        var lossCount = 0
        focusManager.onFocusLost = { lossCount++ }

        focusManager.abandonFocus()
        focusManager.handleFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        assertEquals(0, lossCount)
    }

    @Test
    fun `focus loss while owned is delivered once`() {
        val focusManager = AudioFocusManager(mockk(relaxed = true))
        focusManager.markFocusOwnedForTest()
        var lossCount = 0
        var lossKind: AudioFocusManager.FocusLossKind? = null
        focusManager.onFocusLost = {
            lossCount++
            lossKind = it
        }

        focusManager.handleFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        focusManager.handleFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        assertEquals(1, lossCount)
        assertEquals(AudioFocusManager.FocusLossKind.PERMANENT, lossKind)
    }

    private fun AudioFocusManager.markFocusOwnedForTest() {
        val field = AudioFocusManager::class.java.getDeclaredField("hasFocus")
        field.isAccessible = true
        field.set(this, true)
    }
}
