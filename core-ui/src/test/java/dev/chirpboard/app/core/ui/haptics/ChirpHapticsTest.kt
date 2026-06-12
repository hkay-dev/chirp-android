package dev.chirpboard.app.core.ui.haptics

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

class ChirpHapticsTest {
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
    fun recordStart_vibrates() {
        ChirpHaptics.recordStart(context)
        verify { vibrator.vibrate(any<VibrationEffect>()) }
    }

    @Test
    fun recordStop_vibrates() {
        ChirpHaptics.recordStop(context)
        verify { vibrator.vibrate(any<VibrationEffect>()) }
    }

    @Test
    fun tap_vibrates() {
        ChirpHaptics.tap(context)
        verify { vibrator.vibrate(any<VibrationEffect>()) }
    }

    @Test
    fun delete_vibrates() {
        ChirpHaptics.delete(context)
        verify { vibrator.vibrate(any<VibrationEffect>()) }
    }

    @Test
    fun success_vibrates() {
        ChirpHaptics.success(context)
        verify { vibrator.vibrate(any<VibrationEffect>()) }
    }

    @Test
    fun cursorStep_vibrates() {
        ChirpHaptics.cursorStep(context)
        verify { vibrator.vibrate(any<VibrationEffect>()) }
    }

    @Test
    fun escalate_vibrates() {
        ChirpHaptics.escalate(context)
        verify { vibrator.vibrate(any<VibrationEffect>()) }
    }

    @Test
    fun noVibrator_isNoOp() {
        every { vibrator.hasVibrator() } returns false
        ChirpHaptics.recordStart(context)
        verify(exactly = 0) { vibrator.vibrate(any<VibrationEffect>()) }
    }
}
