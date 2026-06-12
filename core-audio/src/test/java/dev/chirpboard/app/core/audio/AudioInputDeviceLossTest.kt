package dev.chirpboard.app.core.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Device-loss reason propagation (AUD matrix): unplugging the ACTIVE input device must
 * notify the registered listener (the recording service turns that into an
 * INPUT_DEVICE_LOST auto-stop), while unrelated removals stay silent. Also pins the W2
 * fix that the listener survives [AudioInputDeviceSelector.clearActiveDevice] between
 * captures of one service lifetime.
 */
class AudioInputDeviceLossTest {
    private val audioManager = mockk<AudioManager>(relaxed = true)
    private val context =
        mockk<Context> {
            every { getSystemService(AudioManager::class.java) } returns audioManager
        }
    private val audioSettingsStore = mockk<AudioSettingsStore>()
    private val callbackSlot = slot<AudioDeviceCallback>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { audioManager.registerAudioDeviceCallback(capture(callbackSlot), any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun selector(): AudioInputDeviceSelector = AudioInputDeviceSelector(context, audioSettingsStore)

    private fun inputDevice(
        id: Int,
        type: Int = AudioDeviceInfo.TYPE_USB_HEADSET,
        name: String = "Mic $id",
        deviceAddress: String = "addr-$id",
    ): AudioDeviceInfo =
        mockk {
            every { this@mockk.id } returns id
            every { this@mockk.type } returns type
            every { productName } returns name
            every { address } returns deviceAddress
            every { isSource } returns true
        }

    /** Marks [device] as the live capture device via the post-first-read routing refresh. */
    private fun routeCaptureTo(
        selector: AudioInputDeviceSelector,
        device: AudioDeviceInfo,
    ) {
        val record = mockk<AudioRecord> { every { routedDevice } returns device }
        selector.refreshActiveDeviceFromRouting(record)
    }

    @Test
    fun removingTheActiveDevice_notifiesTheDeviceLostListener() {
        val selector = selector()
        val active = inputDevice(id = 42)
        routeCaptureTo(selector, active)
        var lostCount = 0
        selector.setOnActiveDeviceLostListener { lostCount++ }

        callbackSlot.captured.onAudioDevicesRemoved(arrayOf(active))

        assertEquals(1, lostCount)
    }

    @Test
    fun removingAnUnrelatedDevice_doesNotNotify() {
        val selector = selector()
        routeCaptureTo(selector, inputDevice(id = 42))
        var lostCount = 0
        selector.setOnActiveDeviceLostListener { lostCount++ }

        callbackSlot.captured.onAudioDevicesRemoved(arrayOf(inputDevice(id = 7)))

        assertEquals(0, lostCount)
    }

    @Test
    fun deviceLossWithNoActiveCapture_doesNotNotify() {
        val selector = selector()
        val device = inputDevice(id = 42)
        routeCaptureTo(selector, device)
        var lostCount = 0
        selector.setOnActiveDeviceLostListener { lostCount++ }
        selector.clearActiveDevice()

        callbackSlot.captured.onAudioDevicesRemoved(arrayOf(device))

        assertEquals(0, lostCount)
    }

    @Test
    fun listenerSurvivesClearActiveDevice_andFiresForTheNextCapture() {
        // W2 regression pin: clearActiveDevice between captures used to null the listener,
        // leaving the service's later sessions without any device-lost handling.
        val selector = selector()
        var lostCount = 0
        selector.setOnActiveDeviceLostListener { lostCount++ }

        routeCaptureTo(selector, inputDevice(id = 1))
        selector.clearActiveDevice()
        val nextCaptureDevice = inputDevice(id = 2)
        routeCaptureTo(selector, nextCaptureDevice)

        callbackSlot.captured.onAudioDevicesRemoved(arrayOf(nextCaptureDevice))

        assertEquals(1, lostCount)
    }

    @Test
    fun manualPolicyWithMissingRestoredDevice_fallsBackToBestRankedDevice() =
        runBlocking {
            // Backup-restore reality: a manual device key restored from another phone
            // matches nothing here — selection must degrade to the ranked fallback
            // (USB > Bluetooth > wired > built-in), never to "no device", and must
            // report the missing preference so surfaces can show the fallback notice.
            coEvery { audioSettingsStore.currentSettings() } returns
                AudioSettings(
                    inputDevicePolicy = AudioInputDevicePolicy.Manual,
                    manualDeviceAddress = "device:99:Mic from another phone",
                )
            val builtIn =
                inputDevice(id = 1, type = AudioDeviceInfo.TYPE_BUILTIN_MIC, name = "Built-in mic")
            val bluetooth =
                inputDevice(id = 2, type = AudioDeviceInfo.TYPE_BLE_HEADSET, name = "Buds")
            every { audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS) } returns
                arrayOf(bluetooth, builtIn)

            val selector = selector()
            val resolved = selector.resolvePreferredDevice()

            assertEquals(bluetooth.id, resolved?.id)
            assertEquals("Buds", selector.activeDeviceLabel.value)
            assertEquals(
                "Mic from another phone",
                selector.activeDevice.value?.fallbackFromPreferredName,
            )
        }

    @Test
    fun removingTheActiveDevice_reportsItsNameToTheListener() =
        runBlocking {
            // The auto-stop reason must NAME the lost device ("Buds disconnected"),
            // not just say "the microphone disconnected".
            coEvery { audioSettingsStore.currentSettings() } returns
                AudioSettings(inputDevicePolicy = AudioInputDevicePolicy.Automatic)
            val bluetooth =
                inputDevice(id = 2, type = AudioDeviceInfo.TYPE_BLE_HEADSET, name = "Buds")
            every { audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS) } returns
                arrayOf(bluetooth)

            val selector = selector()
            selector.resolvePreferredDevice()
            var lostName: String? = null
            selector.setOnActiveDeviceLostListener { name -> lostName = name }

            callbackSlot.captured.onAudioDevicesRemoved(arrayOf(bluetooth))

            assertEquals("Buds", lostName)
        }
}
