package dev.chirpboard.app.feature.recording.ui

import dev.chirpboard.app.core.audio.ActiveInputDevice
import dev.chirpboard.app.core.audio.AudioInputDeviceKind
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.AudioInputDeviceSummary
import dev.chirpboard.app.core.audio.AudioSettings
import dev.chirpboard.app.core.audio.AudioSettingsStore
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * MIC-005: the Record-screen picker must persist a manual selection (or the automatic
 * policy) through the selector store's single-edit [AudioSettingsStore.selectManualDevice]
 * / [AudioSettingsStore.selectAutomatic] APIs, never the legacy two-edit
 * `setManualDevice` + `setInputDevicePolicy` pair that a racing capture could read torn.
 */
class InputDevicePickerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private val selector =
        mockk<AudioInputDeviceSelector>(relaxed = true) {
            every { availableDevices } returns MutableStateFlow(emptyList())
            every { activeDevice } returns MutableStateFlow<ActiveInputDevice?>(null)
        }

    private val settingsStore =
        mockk<AudioSettingsStore> {
            every { settings } returns flowOf(AudioSettings())
            coEvery { selectManualDevice(any(), any()) } just Runs
            coEvery { selectAutomatic() } just Runs
        }

    private val recordingStateManager =
        mockk<RecordingStateManager> {
            every { state } returns MutableStateFlow<RecordingState>(RecordingState.Idle)
        }

    private fun viewModel() =
        InputDevicePickerViewModel(
            inputDeviceSelector = selector,
            audioSettingsStore = settingsStore,
            recordingStateManager = recordingStateManager,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `selectDevice persists through the atomic selectManualDevice edit`() =
        runTest(dispatcher) {
            val device =
                AudioInputDeviceSummary(
                    id = 7,
                    productName = "Buds",
                    typeLabel = "Bluetooth",
                    kind = AudioInputDeviceKind.Bluetooth,
                    address = "AA:BB:CC",
                    selectionKey = "AA:BB:CC",
                )

            viewModel().selectDevice(device)
            advanceUntilIdle()

            coVerify(exactly = 1) { settingsStore.selectManualDevice("AA:BB:CC", "Buds") }
        }

    @Test
    fun `selectAutomatic flips policy through the atomic selectAutomatic edit`() =
        runTest(dispatcher) {
            viewModel().selectAutomatic()
            advanceUntilIdle()

            coVerify(exactly = 1) { settingsStore.selectAutomatic() }
        }
}
