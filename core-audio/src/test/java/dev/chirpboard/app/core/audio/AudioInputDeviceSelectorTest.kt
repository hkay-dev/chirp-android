package dev.chirpboard.app.core.audio

import android.media.AudioDeviceInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioInputDeviceSelectorTest {
    private fun summary(
        id: Int,
        kind: AudioInputDeviceKind,
        name: String = "Device $id",
        address: String? = null,
        type: Int = typeFor(kind),
        bluetoothNameHidden: Boolean = false,
    ): AudioInputDeviceSummary =
        AudioInputDeviceSummary(
            id = id,
            productName = name,
            typeLabel = AudioInputDeviceSelector.typeLabel(type),
            kind = kind,
            address = address,
            selectionKey = AudioInputDeviceSelector.selectionKeyFor(type, address, name),
            bluetoothNameHidden = bluetoothNameHidden,
        )

    private fun typeFor(kind: AudioInputDeviceKind): Int =
        when (kind) {
            AudioInputDeviceKind.BuiltIn -> AudioDeviceInfo.TYPE_BUILTIN_MIC
            AudioInputDeviceKind.Usb -> AudioDeviceInfo.TYPE_USB_HEADSET
            AudioInputDeviceKind.WiredHeadset -> AudioDeviceInfo.TYPE_WIRED_HEADSET
            AudioInputDeviceKind.BluetoothLe -> AudioDeviceInfo.TYPE_BLE_HEADSET
            AudioInputDeviceKind.Bluetooth -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            AudioInputDeviceKind.Other -> AudioDeviceInfo.TYPE_FM_TUNER
        }

    // --- Priority / ranking ----------------------------------------------------------

    @Test
    fun devicePriority_ranksUsbOverBluetoothOverWiredOverBuiltIn() {
        val usb = AudioInputDeviceSelector.devicePriority(AudioDeviceInfo.TYPE_USB_HEADSET)
        val ble = AudioInputDeviceSelector.devicePriority(AudioDeviceInfo.TYPE_BLE_HEADSET)
        val sco = AudioInputDeviceSelector.devicePriority(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        val wired = AudioInputDeviceSelector.devicePriority(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        val builtIn = AudioInputDeviceSelector.devicePriority(AudioDeviceInfo.TYPE_BUILTIN_MIC)
        val other = AudioInputDeviceSelector.devicePriority(AudioDeviceInfo.TYPE_FM_TUNER)

        assertTrue(usb < ble)
        assertTrue(ble < sco)
        assertTrue(sco < wired)
        assertTrue(wired < builtIn)
        assertTrue(builtIn < other)
    }

    @Test
    fun rankDevices_putsExternalMicsBeforeBuiltIn() {
        val builtIn = summary(1, AudioInputDeviceKind.BuiltIn)
        val bluetooth = summary(2, AudioInputDeviceKind.Bluetooth)
        val usb = summary(3, AudioInputDeviceKind.Usb)
        val wired = summary(4, AudioInputDeviceKind.WiredHeadset)

        val ranked = AudioInputDeviceSelector.rankDevices(listOf(builtIn, wired, bluetooth, usb))

        assertEquals(listOf(3, 2, 4, 1), ranked.map { it.id })
    }

    // --- Selection algorithm matrix ----------------------------------------------------

    @Test
    fun choose_manualPreferredPresent_selectsIt() {
        val builtIn = summary(1, AudioInputDeviceKind.BuiltIn)
        val usb = summary(3, AudioInputDeviceKind.Usb, name = "USB Mic", address = "card=1;device=0")
        val wired = summary(4, AudioInputDeviceKind.WiredHeadset)

        val choice =
            AudioInputDeviceSelector.chooseInputDevice(
                devices = listOf(builtIn, usb, wired),
                policy = AudioInputDevicePolicy.Manual,
                manualKey = wired.selectionKey,
            )

        assertEquals(wired.id, choice.device?.id)
        assertFalse(choice.preferredMissing)
    }

    @Test
    fun choose_manualPreferredAbsent_fallsBackByPriorityAndFlagsIt() {
        val builtIn = summary(1, AudioInputDeviceKind.BuiltIn)
        val bluetooth = summary(2, AudioInputDeviceKind.BluetoothLe)

        val choice =
            AudioInputDeviceSelector.chooseInputDevice(
                devices = listOf(builtIn, bluetooth),
                policy = AudioInputDevicePolicy.Manual,
                manualKey = "card=9;device=9",
            )

        assertEquals(bluetooth.id, choice.device?.id)
        assertTrue(choice.preferredMissing)
    }

    @Test
    fun choose_manualWithoutStoredKey_isNotReportedMissing() {
        val builtIn = summary(1, AudioInputDeviceKind.BuiltIn)

        val choice =
            AudioInputDeviceSelector.chooseInputDevice(
                devices = listOf(builtIn),
                policy = AudioInputDevicePolicy.Manual,
                manualKey = null,
            )

        assertEquals(builtIn.id, choice.device?.id)
        assertFalse(choice.preferredMissing)
    }

    @Test
    fun choose_automatic_picksEachTierOnlyWhenPresent() {
        val builtIn = summary(1, AudioInputDeviceKind.BuiltIn)
        val wired = summary(4, AudioInputDeviceKind.WiredHeadset)
        val bluetooth = summary(2, AudioInputDeviceKind.Bluetooth)
        val usb = summary(3, AudioInputDeviceKind.Usb)

        fun pick(devices: List<AudioInputDeviceSummary>): Int? =
            AudioInputDeviceSelector
                .chooseInputDevice(devices, AudioInputDevicePolicy.Automatic, manualKey = null)
                .device
                ?.id

        assertEquals(usb.id, pick(listOf(builtIn, wired, bluetooth, usb)))
        assertEquals(bluetooth.id, pick(listOf(builtIn, wired, bluetooth)))
        assertEquals(wired.id, pick(listOf(builtIn, wired)))
        assertEquals(builtIn.id, pick(listOf(builtIn)))
        assertNull(pick(emptyList()))
    }

    @Test
    fun choose_preferBuiltIn_selectsBuiltInWhenPresent() {
        val builtIn = summary(1, AudioInputDeviceKind.BuiltIn)
        val usb = summary(3, AudioInputDeviceKind.Usb)

        val choice =
            AudioInputDeviceSelector.chooseInputDevice(
                devices = listOf(usb, builtIn),
                policy = AudioInputDevicePolicy.PreferBuiltIn,
                manualKey = null,
            )

        assertEquals(builtIn.id, choice.device?.id)
        assertFalse(choice.preferredMissing)
    }

    @Test
    fun choose_emptyDeviceList_returnsNoDevice() {
        val choice =
            AudioInputDeviceSelector.chooseInputDevice(
                devices = emptyList(),
                policy = AudioInputDevicePolicy.Manual,
                manualKey = "device:7:Buds",
            )

        assertNull(choice.device)
        assertTrue(choice.preferredMissing)
    }

    // --- Identity stability -------------------------------------------------------------

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
    fun selectionKey_isStableAcrossTransientIds() {
        // The numeric AudioDeviceInfo.id changes on every reconnect/reboot; the key must not.
        val before = summary(10, AudioInputDeviceKind.Bluetooth, name = "Buds", address = "")
        val after = summary(99, AudioInputDeviceKind.Bluetooth, name = "Buds", address = "")

        assertEquals(before.selectionKey, after.selectionKey)
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

    // --- Identity across BLUETOOTH_CONNECT grant/revoke ---------------------------------

    @Test
    fun choose_manualKeyPersistedBeforeBluetoothGrant_stillSelectsTheDeviceAfterGrant() {
        // Pre-grant the BLE device persisted a composite key (blank/hidden name). After the
        // grant it reports a MAC + real name; the stored key must still route to it instead
        // of silently falling back to the ranked-first USB mic.
        val preGrantKey =
            AudioInputDeviceSelector.selectionKeyFor(
                type = AudioDeviceInfo.TYPE_BLE_HEADSET,
                address = "",
                productName = "",
            )
        val usb = summary(3, AudioInputDeviceKind.Usb, name = "USB Mic", address = "card=1;device=0")
        val grantedBle =
            summary(7, AudioInputDeviceKind.BluetoothLe, name = "Buds Pro", address = "AA:BB:CC:DD:EE:FF")

        val choice =
            AudioInputDeviceSelector.chooseInputDevice(
                devices = listOf(usb, grantedBle),
                policy = AudioInputDevicePolicy.Manual,
                manualKey = preGrantKey,
            )

        assertEquals(grantedBle.id, choice.device?.id)
        assertFalse(choice.preferredMissing)
    }

    @Test
    fun bluetoothIdentityFallback_modelNamedCompositeKey_matchesVisibleDeviceOfSameType() {
        // Without BLUETOOTH_CONNECT the platform substitutes the phone's own model for the
        // device name, so that is what old persisted keys contain.
        val key = "device:${AudioDeviceInfo.TYPE_BLE_HEADSET}:Pixel 9 Pro"
        val grantedBle =
            summary(7, AudioInputDeviceKind.BluetoothLe, name = "Buds Pro", address = "AA:BB:CC:DD:EE:FF")

        assertTrue(
            AudioInputDeviceSelector.bluetoothIdentityFallbackMatches(
                summary = grantedBle,
                key = key,
                ownModelName = "Pixel 9 Pro",
            ),
        )
        // A composite key naming a REAL device must not match a different visible device.
        assertFalse(
            AudioInputDeviceSelector.bluetoothIdentityFallbackMatches(
                summary = grantedBle,
                key = "device:${AudioDeviceInfo.TYPE_BLE_HEADSET}:Other Buds",
                ownModelName = "Pixel 9 Pro",
            ),
        )
    }

    @Test
    fun choose_manualKeyPersistedAfterGrant_matchesHiddenDeviceOfSameTypeAfterRevoke() {
        // Reverse transition: a composite key persisted with the real name (visible name,
        // blank address) must keep matching once the name degrades to hidden post-revoke.
        val postGrantKey =
            AudioInputDeviceSelector.selectionKeyFor(
                type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                address = "",
                productName = "Buds Pro",
            )
        val hiddenSco =
            summary(8, AudioInputDeviceKind.Bluetooth, name = "Bluetooth", bluetoothNameHidden = true)

        val choice =
            AudioInputDeviceSelector.chooseInputDevice(
                devices = listOf(summary(1, AudioInputDeviceKind.BuiltIn), hiddenSco),
                policy = AudioInputDevicePolicy.Manual,
                manualKey = postGrantKey,
            )

        assertEquals(hiddenSco.id, choice.device?.id)
        assertFalse(choice.preferredMissing)
    }

    @Test
    fun findDeviceForSelectionKey_exactMatchAlwaysBeatsTheRelaxedFallback() {
        val hiddenFirst =
            summary(5, AudioInputDeviceKind.Bluetooth, name = "Bluetooth", bluetoothNameHidden = true)
        val exact = summary(6, AudioInputDeviceKind.Bluetooth, name = "Buds", address = "")

        val found =
            AudioInputDeviceSelector.findDeviceForSelectionKey(
                devices = listOf(hiddenFirst, exact),
                key = exact.selectionKey,
            )

        assertEquals(exact.id, found?.id)
    }

    @Test
    fun bluetoothIdentityFallback_neverCrossesTypesAndNeverRelaxesAddressKeys() {
        val hiddenBleKey = "device:${AudioDeviceInfo.TYPE_BLE_HEADSET}:"
        val sco = summary(8, AudioInputDeviceKind.Bluetooth, name = "SCO Headset")
        val wired = summary(4, AudioInputDeviceKind.WiredHeadset)
        val hiddenBle =
            summary(9, AudioInputDeviceKind.BluetoothLe, name = "Bluetooth LE", bluetoothNameHidden = true)

        // A hidden BLE key must not match a different Bluetooth type or a wired device.
        assertFalse(AudioInputDeviceSelector.bluetoothIdentityFallbackMatches(sco, hiddenBleKey))
        assertFalse(AudioInputDeviceSelector.bluetoothIdentityFallbackMatches(wired, hiddenBleKey))
        // MAC keys carry no type information; they are reported missing instead of guessed.
        assertNull(
            AudioInputDeviceSelector.findDeviceForSelectionKey(listOf(hiddenBle), "AA:BB:CC:DD:EE:FF"),
        )
        // Wired composite keys are never relaxed either — only Bluetooth identity is
        // permission-dependent.
        assertFalse(
            AudioInputDeviceSelector.bluetoothIdentityFallbackMatches(
                summary(11, AudioInputDeviceKind.WiredHeadset, name = "Other Headset"),
                "device:${AudioDeviceInfo.TYPE_WIRED_HEADSET}:Old Headset",
            ),
        )
    }

    @Test
    fun displayNameFromSelectionKey_recoversCompositeName() {
        assertEquals(
            "BT Headset",
            AudioInputDeviceSelector.displayNameFromSelectionKey("device:7:BT Headset"),
        )
        assertNull(AudioInputDeviceSelector.displayNameFromSelectionKey("card=1;device=0"))
        assertNull(AudioInputDeviceSelector.displayNameFromSelectionKey(null))
    }

    // --- Permission-denied degradation ---------------------------------------------------

    @Test
    fun summaryFor_bluetoothWithoutPermission_degradesToTypeLabelAndFlags() {
        val device =
            mockk<AudioDeviceInfo> {
                every { id } returns 5
                every { type } returns AudioDeviceInfo.TYPE_BLE_HEADSET
                every { address } returns ""
                every { productName } returns ""
            }

        val summary = AudioInputDeviceSelector.summaryFor(device, hasBluetoothPermission = false)

        assertEquals("Bluetooth LE", summary.productName)
        assertTrue(summary.bluetoothNameHidden)
    }

    @Test
    fun summaryFor_bluetoothWithPermission_keepsRealName() {
        val device =
            mockk<AudioDeviceInfo> {
                every { id } returns 5
                every { type } returns AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                every { address } returns "AA:BB:CC:DD:EE:FF"
                every { productName } returns "Sony WH-1000XM5"
            }

        val summary = AudioInputDeviceSelector.summaryFor(device, hasBluetoothPermission = true)

        assertEquals("Sony WH-1000XM5", summary.productName)
        assertFalse(summary.bluetoothNameHidden)
        assertEquals("AA:BB:CC:DD:EE:FF", summary.selectionKey)
    }

    @Test
    fun summaryFor_builtInMic_showsFriendlyNameButKeepsRawKey() {
        val device =
            mockk<AudioDeviceInfo> {
                every { id } returns 1
                every { type } returns AudioDeviceInfo.TYPE_BUILTIN_MIC
                every { address } returns ""
                every { productName } returns "Pixel 9 Pro"
            }

        val summary = AudioInputDeviceSelector.summaryFor(device, hasBluetoothPermission = false)

        assertEquals(AudioInputDeviceSelector.BUILT_IN_DISPLAY_NAME, summary.productName)
        // Key derives from the RAW name so previously persisted selections keep matching.
        assertEquals("device:${AudioDeviceInfo.TYPE_BUILTIN_MIC}:Pixel 9 Pro", summary.selectionKey)
        assertFalse(summary.bluetoothNameHidden)
    }

    @Test
    fun typeLabel_labelsBleHeadsets() {
        assertEquals("Bluetooth LE", AudioInputDeviceSelector.typeLabel(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals("Bluetooth", AudioInputDeviceSelector.typeLabel(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
    }

    // --- Surfaced (picker) device list ------------------------------------------------

    @Test
    fun surfaceableInputDevices_collapsesDuplicateBuiltInMicsAndDropsOtherKinds() {
        // Samsung returns two TYPE_BUILTIN_MIC rows (blank address -> identical composite
        // selectionKey) plus non-recordable "Other" endpoints (telephony/FM as model name).
        val bottomMic = summary(id = 1, kind = AudioInputDeviceKind.BuiltIn, name = "SM-S938U1")
        val referenceMic = summary(id = 2, kind = AudioInputDeviceKind.BuiltIn, name = "SM-S938U1")
        val telephony = summary(id = 3, kind = AudioInputDeviceKind.Other, name = "SM-S938U1")
        val fmTuner = summary(id = 4, kind = AudioInputDeviceKind.Other, name = "SM-S938U1")

        val surfaced =
            AudioInputDeviceSelector.surfaceableInputDevices(
                listOf(bottomMic, referenceMic, telephony, fmTuner),
            )

        assertEquals(1, surfaced.size)
        assertEquals(AudioInputDeviceKind.BuiltIn, surfaced.single().kind)
    }

    @Test
    fun surfaceableInputDevices_keepsDistinctRealMicsIncludingUsb() {
        val builtIn = summary(id = 1, kind = AudioInputDeviceKind.BuiltIn, name = "SM-S938U1")
        val usb = summary(id = 2, kind = AudioInputDeviceKind.Usb, name = "USB Mic", address = "usb:1")
        val bt = summary(id = 3, kind = AudioInputDeviceKind.Bluetooth, name = "Earbuds", address = "AA:BB")

        val surfaced = AudioInputDeviceSelector.surfaceableInputDevices(listOf(builtIn, usb, bt))

        assertEquals(3, surfaced.size)
        assertTrue(surfaced.any { it.kind == AudioInputDeviceKind.Usb })
        assertTrue(surfaced.any { it.kind == AudioInputDeviceKind.Bluetooth })
        assertTrue(surfaced.any { it.kind == AudioInputDeviceKind.BuiltIn })
    }

    @Test
    fun kindFor_mapsUsbAccessoryToUsb() {
        assertEquals(AudioInputDeviceKind.Usb, AudioInputDeviceSelector.kindFor(AudioDeviceInfo.TYPE_USB_ACCESSORY))
    }
}
