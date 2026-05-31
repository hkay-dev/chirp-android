package dev.chirpboard.app.feature.keyboard.haptic

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
    private lateinit var vibratorManager: VibratorManager

    @Before
    fun setup() {
        context = mockk()
        vibrator = mockk()
        vibratorManager = mockk()
        every { vibrator.hasVibrator() } returns true
        every { vibrator.vibrate(any<VibrationEffect>()) } just Runs
        every { vibratorManager.defaultVibrator } returns vibrator
        every { context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) } returns vibratorManager

        mockkStatic(VibrationEffect::class)
        val mockEffect = mockk<VibrationEffect>()
        every { VibrationEffect.createOneShot(any(), any()) } returns mockEffect
        every { VibrationEffect.createWaveform(any(), any(), any()) } returns mockEffect
        every { VibrationEffect.createPredefined(any()) } returns mockEffect
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

    @Test
    fun `onCursorStep vibrates vibrator`() {
        HapticFeedback.onCursorStep(context)
        verify { vibrator.vibrate(any<VibrationEffect>()) }
    }
}
