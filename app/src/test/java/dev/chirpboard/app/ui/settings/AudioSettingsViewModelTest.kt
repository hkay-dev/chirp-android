package dev.chirpboard.app.ui.settings

import app.cash.turbine.test
import dev.chirpboard.app.core.audio.RecordingQualityPreset
import dev.chirpboard.app.feature.keyboard.KeyboardPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        keyboardPreferences = mockk()
        every { keyboardPreferences.microphoneGain } returns MutableStateFlow(1.5f)
        every { keyboardPreferences.recordingQualityPreset } returns MutableStateFlow(RecordingQualityPreset.Balanced)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initializes with preferences values`() =
        runTest {
            val viewModel = AudioSettingsViewModel(keyboardPreferences)
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
            val viewModel = AudioSettingsViewModel(keyboardPreferences)

            viewModel.setMicrophoneGain(2.0f)

            coVerify { keyboardPreferences.setMicrophoneGain(2.0f) }
        }

    @Test
    fun `setRecordingQualityPreset calls preferences`() =
        runTest {
            coEvery { keyboardPreferences.setRecordingQualityPreset(any()) } returns Unit
            val viewModel = AudioSettingsViewModel(keyboardPreferences)

            viewModel.setRecordingQualityPreset(RecordingQualityPreset.High)

            coVerify { keyboardPreferences.setRecordingQualityPreset(RecordingQualityPreset.High) }
        }
}