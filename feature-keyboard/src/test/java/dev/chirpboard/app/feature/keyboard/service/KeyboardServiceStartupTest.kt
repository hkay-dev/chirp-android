package dev.chirpboard.app.feature.keyboard.service

import dev.chirpboard.app.feature.llm.model.ProcessingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardServiceStartupTest {
    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun `observePreferences collects llmEnabled`() =
        runTest(testDispatcher) {
            val llmEnabledFlow = MutableStateFlow(true)
            val microphoneGainFlow = MutableStateFlow(1.0f)

            var llmEnabled = false
            KeyboardServiceStartup.observePreferences(
                scope = backgroundScope,
                llmEnabledFlow = llmEnabledFlow,
                microphoneGainFlow = microphoneGainFlow,
                onLlmEnabledChanged = { llmEnabled = it },
                onMicrophoneGainChanged = {},
            )

            runCurrent()
            assertEquals(true, llmEnabled)

            llmEnabledFlow.value = false
            runCurrent()
            assertEquals(false, llmEnabled)
        }

    @Test
    fun `observePreferences collects microphoneGain`() =
        runTest(testDispatcher) {
            val llmEnabledFlow = MutableStateFlow(true)
            val microphoneGainFlow = MutableStateFlow(1.0f)

            var micGain = 0f
            KeyboardServiceStartup.observePreferences(
                scope = backgroundScope,
                llmEnabledFlow = llmEnabledFlow,
                microphoneGainFlow = microphoneGainFlow,
                onLlmEnabledChanged = {},
                onMicrophoneGainChanged = { micGain = it },
            )

            runCurrent()
            assertEquals(1.0f, micGain)

            microphoneGainFlow.value = 1.5f
            runCurrent()
            assertEquals(1.5f, micGain)
        }

    @Test
    fun `observeProcessingMode collects currentMode`() =
        runTest(testDispatcher) {
            val currentModeFlow = MutableStateFlow<ProcessingMode>(ProcessingMode.Proofread)

            var mode: ProcessingMode? = null
            KeyboardServiceStartup.observeProcessingMode(
                scope = backgroundScope,
                currentModeFlow = currentModeFlow,
                onModeChanged = { mode = it },
            )

            runCurrent()
            assertEquals(ProcessingMode.Proofread, mode)

            currentModeFlow.value = ProcessingMode.Casual
            runCurrent()
            assertEquals(ProcessingMode.Casual, mode)
        }
}