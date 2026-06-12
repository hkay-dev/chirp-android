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

    @Test
    fun `focus regained after transient loss invokes onFocusRegained`() {
        val focusManager = AudioFocusManager(mockk(relaxed = true))
        focusManager.markFocusOwnedForTest()
        var regained = 0
        focusManager.onFocusRegained = { regained++ }

        focusManager.handleFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        focusManager.handleFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(1, regained)
    }

    @Test
    fun `focus gain after permanent loss does not invoke onFocusRegained`() {
        val focusManager = AudioFocusManager(mockk(relaxed = true))
        focusManager.markFocusOwnedForTest()
        var regained = 0
        focusManager.onFocusRegained = { regained++ }

        focusManager.handleFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        focusManager.handleFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(0, regained)
    }

    @Test
    fun `duck-permitted transient loss maps to TRANSIENT like a full transient loss`() {
        // AUD-05 matrix: recording cannot duck, so CAN_DUCK must take the same
        // pause-with-auto-resume path as LOSS_TRANSIENT, never the permanent stop.
        val focusManager = AudioFocusManager(mockk(relaxed = true))
        focusManager.markFocusOwnedForTest()
        val kinds = mutableListOf<AudioFocusManager.FocusLossKind>()
        focusManager.onFocusLost = { kinds.add(it) }

        focusManager.handleFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertEquals(listOf(AudioFocusManager.FocusLossKind.TRANSIENT), kinds)
    }

    @Test
    fun `focus events before any grant are ignored`() {
        val focusManager = AudioFocusManager(mockk(relaxed = true))
        var losses = 0
        var regains = 0
        focusManager.onFocusLost = { losses++ }
        focusManager.onFocusRegained = { regains++ }

        focusManager.handleFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        focusManager.handleFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        focusManager.handleFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(0, losses)
        assertEquals(0, regains)
    }

    @Test
    fun `transient loss keeps ownership so a following permanent loss is still delivered`() {
        // A call comes in (transient pause) and then another app claims focus for good:
        // the permanent loss must still reach the service so it stops with save.
        val focusManager = AudioFocusManager(mockk(relaxed = true))
        focusManager.markFocusOwnedForTest()
        val kinds = mutableListOf<AudioFocusManager.FocusLossKind>()
        focusManager.onFocusLost = { kinds.add(it) }

        focusManager.handleFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        focusManager.handleFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        assertEquals(
            listOf(
                AudioFocusManager.FocusLossKind.TRANSIENT,
                AudioFocusManager.FocusLossKind.PERMANENT,
            ),
            kinds,
        )
    }

    @Test
    fun `repeated transient interruption cycles deliver every loss and regain`() {
        // Two back-to-back calls during one recording: each loss pauses and each
        // regain may auto-resume, so none of the four events may be swallowed.
        val focusManager = AudioFocusManager(mockk(relaxed = true))
        focusManager.markFocusOwnedForTest()
        var losses = 0
        var regains = 0
        focusManager.onFocusLost = { losses++ }
        focusManager.onFocusRegained = { regains++ }

        focusManager.handleFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        focusManager.handleFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        focusManager.handleFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        focusManager.handleFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(2, losses)
        assertEquals(2, regains)
    }

    private fun AudioFocusManager.markFocusOwnedForTest() {
        val field = AudioFocusManager::class.java.getDeclaredField("hasFocus")
        field.isAccessible = true
        field.set(this, true)
    }
}
