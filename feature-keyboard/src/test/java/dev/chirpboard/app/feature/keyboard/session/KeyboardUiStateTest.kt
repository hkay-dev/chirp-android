package dev.chirpboard.app.feature.keyboard.session

import dev.chirpboard.app.core.transcription.InlineTranscriptionPhase
import dev.chirpboard.app.core.llm.ProcessingMode
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
    fun `initializing banner stays present across non-idle dictation phases`() {
        // UI-2 mapping half: the banner must not be force-hidden just because the panel left
        // Idle, or it would reflow once per dictation while the model is still warming.
        for (phase in listOf(
            InlineTranscriptionPhase.LoadingModel(null),
            InlineTranscriptionPhase.Transcribing,
            InlineTranscriptionPhase.Polishing,
        )) {
            val state =
                mapKeyboardUiState(
                    isRecording = false,
                    transcriptionPhase = phase,
                    modelBanner = ModelBannerState.Initializing,
                    modelInitFailedMessage = null,
                    llmEnabled = true,
                    processingMode = ProcessingMode.Proofread,
                    availableModes = emptyList(),
                    permissionError = null,
                )
            assertEquals(ModelBannerState.Initializing, state.modelBanner)
        }
    }

    @Test
    fun `recording keeps warming banner instead of breathing it out`() {
        val state =
            mapKeyboardUiState(
                isRecording = true,
                transcriptionPhase = InlineTranscriptionPhase.Idle,
                modelBanner = ModelBannerState.Initializing,
                modelInitFailedMessage = null,
                llmEnabled = true,
                processingMode = ProcessingMode.Proofread,
                availableModes = emptyList(),
                permissionError = null,
            )
        assertEquals(VoicePanelPhase.Recording, state.voicePanel)
        assertEquals(ModelBannerState.Initializing, state.modelBanner)
    }

    @Test
    fun `permission error suppresses the model banner`() {
        val state =
            mapKeyboardUiState(
                isRecording = false,
                transcriptionPhase = InlineTranscriptionPhase.Idle,
                modelBanner = ModelBannerState.Initializing,
                modelInitFailedMessage = null,
                llmEnabled = true,
                processingMode = ProcessingMode.Proofread,
                availableModes = emptyList(),
                permissionError = "Microphone permission required",
            )
        assertEquals(ModelBannerState.None, state.modelBanner)
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
