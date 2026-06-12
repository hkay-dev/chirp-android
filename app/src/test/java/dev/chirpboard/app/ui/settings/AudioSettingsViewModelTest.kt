package dev.chirpboard.app.ui.settings

import app.cash.turbine.test
import dev.chirpboard.app.core.audio.AudioInputDeviceKind
import dev.chirpboard.app.core.audio.AudioInputDevicePolicy
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.AudioInputDeviceSummary
import dev.chirpboard.app.core.audio.AudioSettings
import dev.chirpboard.app.core.audio.AudioSettingsStore
import dev.chirpboard.app.core.audio.RecordingOutputFormat
import dev.chirpboard.app.core.audio.RecordingQualityPreset
import dev.chirpboard.app.core.preferences.KeyboardPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioSettingsViewModelTest {
    private lateinit var keyboardPreferences: KeyboardPreferences
    private lateinit var audioSettingsStore: AudioSettingsStore
    private lateinit var inputDeviceSelector: AudioInputDeviceSelector
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        keyboardPreferences = mockk()
        audioSettingsStore = mockk(relaxed = true)
        inputDeviceSelector = mockk(relaxed = true)
        every { keyboardPreferences.microphoneGain } returns MutableStateFlow(1.5f)
        every { keyboardPreferences.recordingQualityPreset } returns MutableStateFlow(RecordingQualityPreset.Balanced)
        every { keyboardPreferences.outputFormat } returns MutableStateFlow(RecordingOutputFormat.M4A)
        every { audioSettingsStore.settings } returns
            flowOf(
                AudioSettings(
                    microphoneGain = 1.5f,
                    recordingQualityPreset = RecordingQualityPreset.Balanced,
                    inputDevicePolicy = AudioInputDevicePolicy.Automatic,
                ),
            )
        every { inputDeviceSelector.activeDeviceLabel } returns MutableStateFlow(null)
        every { inputDeviceSelector.devicesChangedTick } returns MutableStateFlow(0L)
        coEvery { inputDeviceSelector.listInputDevices() } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): AudioSettingsViewModel =
        AudioSettingsViewModel(keyboardPreferences, audioSettingsStore, inputDeviceSelector)

    @Test
    fun `initializes with preferences values`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.microphoneGain.test {
                assertEquals(1.5f, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            viewModel.recordingQualityPreset.test {
                assertEquals(RecordingQualityPreset.Balanced, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `setMicrophoneGain calls preferences`() =
        runTest {
            coEvery { keyboardPreferences.setMicrophoneGain(any()) } returns Unit
            val viewModel = createViewModel()

            viewModel.setMicrophoneGain(2.0f)

            coVerify { keyboardPreferences.setMicrophoneGain(2.0f) }
        }

    @Test
    fun `setRecordingQualityPreset calls preferences`() =
        runTest {
            coEvery { keyboardPreferences.setRecordingQualityPreset(any()) } returns Unit
            val viewModel = createViewModel()

            viewModel.setRecordingQualityPreset(RecordingQualityPreset.High)

            coVerify { keyboardPreferences.setRecordingQualityPreset(RecordingQualityPreset.High) }
        }

    @Test
    fun `setOutputFormat calls preferences`() =
        runTest {
            coEvery { keyboardPreferences.setOutputFormat(any()) } returns Unit
            val viewModel = createViewModel()

            viewModel.setOutputFormat(RecordingOutputFormat.MP3)

            coVerify { keyboardPreferences.setOutputFormat(RecordingOutputFormat.MP3) }
        }

    @Test
    fun `device hot-plug refreshes the input device list`() =
        runTest {
            val tick = MutableStateFlow(0L)
            every { inputDeviceSelector.devicesChangedTick } returns tick
            createViewModel()

            tick.value = 1L

            coVerify(atLeast = 2) { inputDeviceSelector.listInputDevices() }
        }

    @Test
    fun `setManualInputDevice persists the selection key, display name, and manual policy atomically`() =
        runTest {
            val viewModel = createViewModel()
            val device =
                AudioInputDeviceSummary(
                    id = 7,
                    productName = "BT Headset",
                    typeLabel = "Bluetooth",
                    kind = AudioInputDeviceKind.Bluetooth,
                    address = "",
                    selectionKey = "device:7:BT Headset",
                )

            viewModel.setManualInputDevice(device)

            // Single atomic edit — never the old two-call setManualDevice + setInputDevicePolicy pair.
            coVerify { audioSettingsStore.selectManualDevice("device:7:BT Headset", "BT Headset") }
            coVerify(exactly = 0) { audioSettingsStore.setManualDevice(any(), any()) }
            coVerify(exactly = 0) { audioSettingsStore.setInputDevicePolicy(any()) }
        }

    @Test
    fun `setInputDevicePolicy Automatic flips policy atomically`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.setInputDevicePolicy(AudioInputDevicePolicy.Automatic)

            coVerify { audioSettingsStore.selectAutomatic() }
            coVerify(exactly = 0) { audioSettingsStore.setInputDevicePolicy(any()) }
        }

    @Test
    fun `setInputDevicePolicy Manual flips policy without writing a manual key`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.setInputDevicePolicy(AudioInputDevicePolicy.Manual)

            coVerify { audioSettingsStore.setInputDevicePolicy(AudioInputDevicePolicy.Manual) }
            coVerify(exactly = 0) { audioSettingsStore.selectManualDevice(any(), any()) }
            coVerify(exactly = 0) { audioSettingsStore.selectAutomatic() }
        }
}
