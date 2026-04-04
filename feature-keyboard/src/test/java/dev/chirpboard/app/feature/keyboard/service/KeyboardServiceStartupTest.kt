package dev.chirpboard.app.feature.keyboard.service

import dev.chirpboard.app.feature.keyboard.KeyboardPreferences
import dev.chirpboard.app.feature.llm.model.ProcessingMode
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardServiceStartupTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun `observePreferences collects llmEnabled`() =
        testScope.runTest {
            val prefs = mockk<KeyboardPreferences>()
            val flow = MutableStateFlow(true)
            every { prefs.llmEnabled } returns flow
            every { prefs.microphoneGain } returns MutableStateFlow(1.0f)

            var llmEnabled = false
            KeyboardServiceStartup.observePreferences(
                scope = testScope,
                keyboardPreferences = prefs,
                onLlmEnabledChanged = { llmEnabled = it },
                onMicrophoneGainChanged = {},
            )

            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(true, llmEnabled)

            flow.value = false
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(false, llmEnabled)
        }

    @Test
    fun `observePreferences collects microphoneGain`() =
        testScope.runTest {
            val prefs = mockk<KeyboardPreferences>()
            val flow = MutableStateFlow(1.0f)
            every { prefs.llmEnabled } returns MutableStateFlow(true)
            every { prefs.microphoneGain } returns flow

            var micGain = 0f
            KeyboardServiceStartup.observePreferences(
                scope = testScope,
                keyboardPreferences = prefs,
                onLlmEnabledChanged = {},
                onMicrophoneGainChanged = { micGain = it },
            )

            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(1.0f, micGain)

            flow.value = 1.5f
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(1.5f, micGain)
        }

    @Test
    fun `observeProcessingMode collects currentMode`() =
        testScope.runTest {
            val modeRepo = mockk<ProcessingModeRepository>()
            val flow = MutableStateFlow<ProcessingMode>(ProcessingMode.Proofread)
            every { modeRepo.currentMode } returns flow

            var mode: ProcessingMode? = null
            KeyboardServiceStartup.observeProcessingMode(
                scope = testScope,
                modeRepository = modeRepo,
                onModeChanged = { mode = it },
            )

            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(ProcessingMode.Proofread, mode)

            flow.value = ProcessingMode.Casual
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(ProcessingMode.Casual, mode)
        }
}
