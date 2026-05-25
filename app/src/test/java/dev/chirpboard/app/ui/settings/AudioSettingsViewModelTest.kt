package dev.chirpboard.app.ui.settings

import app.cash.turbine.test
import dev.chirpboard.app.core.audio.AudioInputDevicePolicy
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.AudioSettings
import dev.chirpboard.app.core.audio.AudioSettingsStore
import dev.chirpboard.app.core.audio.RecordingQualityPreset
import dev.chirpboard.app.feature.keyboard.KeyboardPreferences
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
        every { audioSettingsStore.settings } returns
            flowOf(
                AudioSettings(
                    microphoneGain = 1.5f,
                    recordingQualityPreset = RecordingQualityPreset.Balanced,
                    inputDevicePolicy = AudioInputDevicePolicy.Automatic,
                ),
            )
        every { inputDeviceSelector.activeDeviceLabel } returns MutableStateFlow(null)
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
}
