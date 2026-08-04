package dev.chirpboard.app.core.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * MIC-020 item 18 — rapid selection thrash collapses to the FINAL selection.
 *
 * The audit found no test exercising more than one selection change per session. A user
 * can tap device A, B, A again, then Automatic in quick succession while a capture is
 * live; every tap is a one-shot atomic settings write ([AudioSettingsStore.selectManualDevice]
 * / [AudioSettingsStore.selectAutomatic]), so the intent collapses to whatever was tapped
 * last. The contract is that selection applies at the NEXT engine start: the live session
 * keeps its device, and the following stop->start resolves the device EXACTLY ONCE from the
 * final persisted state — no double-start and no stale earlier key honored.
 *
 * Pinned against the real DataStore-backed [AudioSettingsStore] (so the thrash collapses
 * through genuine edits) wired into the selector over a mocked [AudioManager].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RapidSelectionThrashTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val audioManager = mockk<AudioManager>(relaxed = true)
    private val context =
        mockk<Context> {
            every { getSystemService(AudioManager::class.java) } returns audioManager
        }
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

    private fun store(): AudioSettingsStore {
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope,
                produceFile = { File(temporaryFolder.root, "audio_settings_thrash.preferences_pb") },
            )
        return AudioSettingsStore(
            dataStore = dataStore,
            migrationSource = ThrashMigrationSource(),
        )
    }

    private fun inputDevice(
        id: Int,
        type: Int,
        name: String,
        deviceAddress: String,
    ): AudioDeviceInfo =
        mockk {
            every { this@mockk.id } returns id
            every { this@mockk.type } returns type
            every { productName } returns name
            every { address } returns deviceAddress
            every { isSource } returns true
        }

    @Test
    fun thrashingSelectionDuringALiveSession_usesTheFinalSelectionExactlyOnceOnTheNextStart() =
        testScope.runTest {
            // Three connected mics. Selection keys are derived exactly as the picker derives
            // them so the manual keys we thrash through are the real persisted values.
            val usb =
                inputDevice(id = 1, type = AudioDeviceInfo.TYPE_USB_HEADSET, name = "USB Mic", deviceAddress = "usb-1")
            val wired =
                inputDevice(
                    id = 2,
                    type = AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    name = "Wired Mic",
                    deviceAddress = "wired-2",
                )
            val builtIn =
                inputDevice(
                    id = 3,
                    type = AudioDeviceInfo.TYPE_BUILTIN_MIC,
                    name = "Pixel",
                    deviceAddress = "",
                )
            // Count every device enumeration so we can assert per-session resolution deltas
            // (construction and the device callback also enumerate, so absolute counts are
            // meaningless — only the change across each step matters).
            val enumerations = AtomicInteger(0)
            every { audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS) } answers {
                enumerations.incrementAndGet()
                arrayOf(usb, wired, builtIn)
            }

            // The two thrashed manual keys deliberately point at NON-USB devices, so the
            // final Automatic pick (USB, the top-ranked device) differs from every key the
            // user thrashed through: honoring any stale key would resolve wired/built-in
            // instead and fail the assertion below.
            val keyA = // wired
                AudioInputDeviceSelector.selectionKeyFor(AudioDeviceInfo.TYPE_WIRED_HEADSET, "wired-2", "Wired Mic")
            val keyB = // built-in
                AudioInputDeviceSelector.selectionKeyFor(AudioDeviceInfo.TYPE_BUILTIN_MIC, "", "Pixel")

            val store = store()
            val selector = AudioInputDeviceSelector(context, store)

            // A live session is already running on a previously resolved device (session
            // token 1); the thrashing taps below must not start any new capture.
            selector.resolvePreferredDevice()
            val enumerationsAfterLiveStart = enumerations.get()

            // Rapid thrash WHILE the session is live: A -> B -> A -> Automatic. Each is a
            // single atomic write; the later one fully supersedes the earlier.
            store.selectManualDevice(keyA, displayName = "Wired Mic") // A
            store.selectManualDevice(keyB, displayName = "Pixel") // B
            store.selectManualDevice(keyA, displayName = "Wired Mic") // A again
            store.selectAutomatic() // final selection wins

            // The live session never re-resolved during the thrash: zero new enumerations.
            assertEquals(enumerationsAfterLiveStart, enumerations.get())

            // stop -> start: the next engine start resolves the device exactly once,
            // honoring the FINAL selection (Automatic -> top-ranked = built-in), never any
            // earlier thrashed key (which would have resolved wired or built-in).
            val resolved = selector.resolvePreferredDevice()

            assertEquals(builtIn.id, resolved?.id)
            assertEquals("Built-in microphone", selector.activeDeviceLabel.value)
            // Automatic never reports a missing preferred device, so no stale-key fallback notice.
            assertEquals(null, selector.activeDevice.value?.fallbackFromPreferredName)
            // Exactly one device enumeration for the new session: no double-start.
            assertEquals(enumerationsAfterLiveStart + 1, enumerations.get())

            // The stale manual key is retained in storage (selectAutomatic leaves it dormant)
            // but is deliberately NOT honored under the Automatic policy.
            val settings = store.currentSettings()
            assertEquals(AudioInputDevicePolicy.Automatic, settings.inputDevicePolicy)
            assertEquals(keyA, settings.manualDeviceAddress)
        }
}

private class ThrashMigrationSource : AudioSettingsMigrationSource {
    override suspend fun readLegacyKeyboardMicrophoneGain(): Float? = null

    override fun readLegacyAppMicrophoneGain(): Float? = null
}
