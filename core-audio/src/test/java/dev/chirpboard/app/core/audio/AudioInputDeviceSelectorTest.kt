package dev.chirpboard.app.core.audio

import android.media.AudioDeviceInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        // LE Audio ranks alongside classic Bluetooth, just above SCO, and both rank
        // below built-in/wired (intentional update for AUD: TYPE_BLE_HEADSET support).
        assertEquals(3, AudioInputDeviceSelector.devicePriority(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(4, AudioInputDeviceSelector.devicePriority(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertTrue(
            AudioInputDeviceSelector.devicePriority(AudioDeviceInfo.TYPE_BLE_HEADSET) >
                AudioInputDeviceSelector.devicePriority(AudioDeviceInfo.TYPE_WIRED_HEADSET),
        )
    }

    @Test
    fun typeLabel_labelsBleHeadsets() {
        assertEquals("Bluetooth LE", AudioInputDeviceSelector.typeLabel(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals("Bluetooth", AudioInputDeviceSelector.typeLabel(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
    }

    @Test
    fun selectionKeyFor_usesAddressWhenPresent() {
        assertEquals(
            "bottom",
            AudioInputDeviceSelector.selectionKeyFor(
                type = AudioDeviceInfo.TYPE_BUILTIN_MIC,
                address = "bottom",
                productName = "Built-in",
            ),
        )
    }

    @Test
    fun selectionKeyFor_blankAddressFallsBackToTypeAndName() {
        val key =
            AudioInputDeviceSelector.selectionKeyFor(
                type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                address = "",
                productName = "BT Headset",
            )

        assertEquals("device:${AudioDeviceInfo.TYPE_BLUETOOTH_SCO}:BT Headset", key)
    }

    @Test
    fun matchesSelectionKey_matchesBlankAddressDeviceByCompositeKey() {
        val device =
            mockk<AudioDeviceInfo> {
                every { type } returns AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                every { address } returns ""
                every { productName } returns "BT Headset"
            }
        val key =
            AudioInputDeviceSelector.selectionKeyFor(
                type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                address = "",
                productName = "BT Headset",
            )

        assertTrue(AudioInputDeviceSelector.matchesSelectionKey(device, key))
        assertFalse(AudioInputDeviceSelector.matchesSelectionKey(device, "device:7:Other Headset"))
        assertFalse(AudioInputDeviceSelector.matchesSelectionKey(device, null))
    }

    @Test
    fun matchesSelectionKey_matchesByRealAddress() {
        val device =
            mockk<AudioDeviceInfo> {
                every { type } returns AudioDeviceInfo.TYPE_USB_HEADSET
                every { address } returns "card=1;device=0"
                every { productName } returns "USB Mic"
            }

        assertTrue(AudioInputDeviceSelector.matchesSelectionKey(device, "card=1;device=0"))
        assertFalse(AudioInputDeviceSelector.matchesSelectionKey(device, "card=2;device=0"))
    }
}
