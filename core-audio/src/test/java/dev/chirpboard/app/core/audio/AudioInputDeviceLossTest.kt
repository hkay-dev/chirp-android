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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Device-loss reason propagation (AUD matrix): unplugging the ACTIVE input device must
 * notify the registered listener (the recording service turns that into an
 * INPUT_DEVICE_LOST auto-stop), while unrelated removals stay silent. Also pins the W2
 * fix that the listener survives [AudioInputDeviceSelector.clearActiveDevice] between
 * captures of one service lifetime, plus the session-state hardening: shared
 * [AudioInputDeviceSelector.deviceLostEvents], session-token-guarded clears, and the
 * locked publish ordering that keeps the removal callback's view consistent.
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
    fun manualPolicyWithMissingRestoredDevice_fallsBackToBestRankedDevice() =
        runBlocking {
            // Backup-restore reality: a manual device key restored from another phone
            // matches nothing here — selection must degrade to the ranked fallback
            // (built-in > USB > wired > Bluetooth), never to "no device", and must
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

            assertEquals(builtIn.id, resolved?.id)
            assertEquals("Built-in microphone", selector.activeDeviceLabel.value)
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

    @Test
    fun manualKeyForANonRecordableEndpoint_fallsBackToARecordableDevice() =
        runBlocking {
            // MIC-011: a pre-dedup manual key could pin a telephony/FM "Other" endpoint
            // the picker no longer shows. Capture-start selection now runs over the
            // recordable list, so the key degrades to the ranked fallback + notice —
            // consistent with the picker's "not connected" row.
            val poisonedKey =
                AudioInputDeviceSelector.selectionKeyFor(
                    type = AudioDeviceInfo.TYPE_FM_TUNER,
                    address = "",
                    productName = "SM-S938U1",
                )
            coEvery { audioSettingsStore.currentSettings() } returns
                AudioSettings(
                    inputDevicePolicy = AudioInputDevicePolicy.Manual,
                    manualDeviceAddress = poisonedKey,
                    manualDeviceName = "SM-S938U1",
                )
            val fmTuner =
                inputDevice(id = 9, type = AudioDeviceInfo.TYPE_FM_TUNER, name = "SM-S938U1", deviceAddress = "")
            val builtIn =
                inputDevice(id = 1, type = AudioDeviceInfo.TYPE_BUILTIN_MIC, name = "SM-S938U1", deviceAddress = "")
            every { audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS) } returns
                arrayOf(fmTuner, builtIn)

            val selector = selector()
            val resolved = selector.resolvePreferredDevice()

            assertEquals(builtIn.id, resolved?.id)
            assertEquals(AudioInputDeviceSelector.BUILT_IN_DISPLAY_NAME, selector.activeDeviceLabel.value)
            assertEquals("SM-S938U1", selector.activeDevice.value?.fallbackFromPreferredName)
        }

    @Test
    fun clearActiveDevice_withAStaleSessionToken_doesNotClobberTheNewerSession() =
        runBlocking {
            // MIC-003 clobber race: a finished session's late clear (the service's stop
            // lifecycle) must not wipe the state a newer session already published.
            // Tokens increase monotonically from 1 per publication.
            coEvery { audioSettingsStore.currentSettings() } returns
                AudioSettings(inputDevicePolicy = AudioInputDevicePolicy.Automatic)
            val first = inputDevice(id = 1, name = "Mic A")
            val second = inputDevice(id = 2, name = "Mic B")
            every { audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS) } returns arrayOf(first)

            val selector = selector()
            selector.resolvePreferredDevice() // session token 1
            every { audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS) } returns arrayOf(second)
            selector.resolvePreferredDevice() // session token 2

            selector.clearActiveDevice(sessionToken = 1L)
            assertEquals("Mic B", selector.activeDeviceLabel.value)
            assertEquals(second.id, selector.activeDevice.value?.summary?.id)

            selector.clearActiveDevice(sessionToken = 2L)
            assertNull(selector.activeDevice.value)
            assertNull(selector.activeDeviceLabel.value)
        }

    @Test
    fun refreshActiveDeviceFromRouting_followsEveryReroute() {
        // MIC-013: the routing listener funnels every mid-session reroute through the
        // refresh, so the published active device must track each change, not just the
        // first post-start correction.
        val selector = selector()
        val usb = inputDevice(id = 1, name = "USB Mic")
        val builtIn =
            inputDevice(id = 2, type = AudioDeviceInfo.TYPE_BUILTIN_MIC, name = "Pixel", deviceAddress = "")

        routeCaptureTo(selector, usb)
        assertEquals("USB Mic", selector.activeDeviceLabel.value)

        routeCaptureTo(selector, builtIn)
        assertEquals(AudioInputDeviceSelector.BUILT_IN_DISPLAY_NAME, selector.activeDeviceLabel.value)
        assertEquals(builtIn.id, selector.activeDevice.value?.summary?.id)
    }

    @Test
    fun deviceLost_reachesEveryFlowCollectorAndTheLegacyListener() =
        runBlocking {
            // MIC-014: the loss event is shared (keyboard hint, recognition advisory)
            // while the service's legacy lambda registration keeps firing unchanged.
            val selector = selector()
            val active = inputDevice(id = 42, name = "Buds")
            routeCaptureTo(selector, active)
            var legacyName: String? = null
            selector.setOnActiveDeviceLostListener { legacyName = it }
            val collectorA = mutableListOf<DeviceLostEvent>()
            val collectorB = mutableListOf<DeviceLostEvent>()
            val jobA = launch { selector.deviceLostEvents.collect { collectorA += it } }
            val jobB = launch { selector.deviceLostEvents.collect { collectorB += it } }
            yield() // let both collectors subscribe

            callbackSlot.captured.onAudioDevicesRemoved(arrayOf(active))
            yield() // deliver the buffered emission to the collectors

            val expected = listOf(DeviceLostEvent(deviceId = 42, deviceName = "Buds"))
            assertEquals(expected, collectorA)
            assertEquals(expected, collectorB)
            assertEquals("Buds", legacyName)
            jobA.cancel()
            jobB.cancel()
        }

    @Test
    fun removalOfThePreviousSessionsDevice_afterANewResolve_staysSilent() =
        runBlocking {
            // MIC-002: right after a stop/start cycle the OLD session's device may be
            // unplugged while the NEW session runs on another device — no auto-stop.
            coEvery { audioSettingsStore.currentSettings() } returns
                AudioSettings(inputDevicePolicy = AudioInputDevicePolicy.Automatic)
            val oldDevice = inputDevice(id = 1, name = "Old Mic")
            val newDevice = inputDevice(id = 2, name = "New Mic")
            every { audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS) } returns arrayOf(oldDevice)

            val selector = selector()
            selector.resolvePreferredDevice()
            every { audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS) } returns arrayOf(newDevice)
            selector.resolvePreferredDevice()
            var lostCount = 0
            selector.setOnActiveDeviceLostListener { lostCount++ }

            callbackSlot.captured.onAudioDevicesRemoved(arrayOf(oldDevice))

            assertEquals(0, lostCount)
        }

    @Test
    fun concurrentResolveAndRemoval_neverObservesAnIdWithoutAPublishedSummary() {
        // MIC-002 visibility/ordering: the main-thread removal callback keys on the
        // active id, so whenever it fires the published summary must already exist.
        coEvery { audioSettingsStore.currentSettings() } returns
            AudioSettings(inputDevicePolicy = AudioInputDevicePolicy.Automatic)
        val device = inputDevice(id = 42, name = "USB Mic")
        every { audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS) } returns arrayOf(device)

        val selector = selector()
        val sawTornState = AtomicBoolean(false)
        selector.setOnActiveDeviceLostListener {
            if (selector.activeDevice.value == null) sawTornState.set(true)
        }

        val iterations = 50
        val resolver =
            thread {
                repeat(iterations) { runBlocking { selector.resolvePreferredDevice() } }
            }
        repeat(iterations) {
            callbackSlot.captured.onAudioDevicesRemoved(arrayOf(device))
        }
        resolver.join()

        assertFalse(sawTornState.get())
    }
}
