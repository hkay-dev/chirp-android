package dev.chirpboard.app.feature.keyboard.session

import dev.chirpboard.app.core.transcription.InlineTranscriptionPhase
import dev.chirpboard.app.feature.llm.model.ProcessingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardUiStateTest {
    @Test
    fun `idle with model initializing shows banner`() {
        val state =
            mapKeyboardUiState(
                isRecording = false,
                transcriptionPhase = InlineTranscriptionPhase.Idle,
                modelBanner = ModelBannerState.Initializing,
                modelInitFailedMessage = null,
                llmEnabled = true,
                processingMode = ProcessingMode.Proofread,
                availableModes = emptyList(),
                permissionError = null,
            )
        assertEquals(VoicePanelPhase.Idle, state.voicePanel)
        assertEquals(ModelBannerState.Initializing, state.modelBanner)
        assertTrue(state.showTypingControls)
    }

    @Test
    fun `recording shows recording actions`() {
        val state =
            mapKeyboardUiState(
                isRecording = true,
                transcriptionPhase = InlineTranscriptionPhase.Idle,
                modelBanner = ModelBannerState.None,
                modelInitFailedMessage = null,
                llmEnabled = true,
                processingMode = ProcessingMode.Proofread,
                availableModes = emptyList(),
                permissionError = null,
            )
        assertEquals(VoicePanelPhase.Recording, state.voicePanel)
        assertTrue(state.showRecordingActions)
    }

    @Test
    fun `loading model phase maps correctly`() {
        val state =
            mapKeyboardUiState(
                isRecording = false,
                transcriptionPhase = InlineTranscriptionPhase.LoadingModel(null),
                modelBanner = ModelBannerState.None,
                modelInitFailedMessage = null,
                llmEnabled = true,
                processingMode = ProcessingMode.Proofread,
                availableModes = emptyList(),
                permissionError = null,
            )
        assertEquals(VoicePanelPhase.LoadingModel, state.voicePanel)
    }
}
