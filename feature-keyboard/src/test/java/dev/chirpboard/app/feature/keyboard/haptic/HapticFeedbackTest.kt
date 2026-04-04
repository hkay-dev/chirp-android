package dev.chirpboard.app.feature.keyboard.haptic

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

class HapticFeedbackTest {
    private lateinit var context: Context
    private lateinit var vibrator: Vibrator

    @Before
    fun setup() {
        context = mockk()
        vibrator = mockk()
        every { vibrator.hasVibrator() } returns true
        every { vibrator.vibrate(any<VibrationEffect>()) } just Runs
        every { context.getSystemService(Context.VIBRATOR_SERVICE) } returns vibrator

        mockkStatic(VibrationEffect::class)
        val mockEffect = mockk<VibrationEffect>()
        every { VibrationEffect.createOneShot(any(), any()) } returns mockEffect
        every { VibrationEffect.createWaveform(any(), any(), any()) } returns mockEffect
    }

    @After
    fun teardown() {
        unmockkStatic(VibrationEffect::class)
    }

    @Test
    fun `onRecordStart vibrates vibrator`() {
        HapticFeedback.onRecordStart(context)
        verify { vibrator.vibrate(any<VibrationEffect>()) }
    }

    @Test
    fun `onRecordStop vibrates vibrator`() {
        HapticFeedback.onRecordStop(context)
        verify { vibrator.vibrate(any<VibrationEffect>()) }
    }
}
