package dev.chirpboard.app.core.audio

import android.media.AudioDeviceInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioInputDeviceSelectorTest {
    @Test
    fun rankDevices_prefersBuiltInOverBluetooth() {
        val builtIn =
            mockk<AudioDeviceInfo> {
                every { id } returns 1
                every { type } returns AudioDeviceInfo.TYPE_BUILTIN_MIC
                every { productName } returns "Built-in"
            }
        val bluetooth =
            mockk<AudioDeviceInfo> {
                every { id } returns 2
                every { type } returns AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                every { productName } returns "BT Headset"
            }

        val ranked = AudioInputDeviceSelector.rankDevices(listOf(bluetooth, builtIn))

        assertEquals(builtIn.id, ranked.first().id)
    }

    @Test
    fun devicePriority_ordersExpectedTypes() {
        assertEquals(0, AudioInputDeviceSelector.devicePriority(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals(3, AudioInputDeviceSelector.devicePriority(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
    }
}
