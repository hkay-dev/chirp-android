package dev.chirpboard.app.feature.keyboard.session

import androidx.annotation.StringRes
import dev.chirpboard.app.core.llm.ProcessingMode
import dev.chirpboard.app.core.llm.ProcessingModeListItem
import dev.chirpboard.app.core.transcription.InlineTranscriptionPhase
import dev.chirpboard.app.feature.keyboard.R

enum class VoicePanelPhase {
    Idle,
    Recording,
    LoadingModel,
    Transcribing,
    Polishing,
    Error,
    LlmError,
}

enum class ModelBannerState {
    None,
    Initializing,
    NotDownloaded,
    InitFailed,
}

data class KeyboardUiState(
    val voicePanel: VoicePanelPhase,
    val modelLoadProgress: Float?,
    val modelBanner: ModelBannerState,
    val modelInitFailedMessage: String? = null,
    val llmEnabled: Boolean,
    val processingMode: ProcessingMode,
    val availableModes: List<ProcessingModeListItem> = emptyList(),
    val errorOverlay: String? = null,
    val errorMessage: String? = null,
    val llmErrorMessage: String? = null,
    val showTypingControls: Boolean = true,
    val showRecordingActions: Boolean = false,
    val settingsEnabled: Boolean = true,
) {
    @StringRes
    fun statusLabelRes(): Int? =
        when (voicePanel) {
            VoicePanelPhase.Recording -> R.string.keyboard_status_recording
            VoicePanelPhase.LoadingModel -> R.string.keyboard_loading_speech_model
            VoicePanelPhase.Transcribing -> R.string.keyboard_transcribing
            VoicePanelPhase.Polishing -> R.string.keyboard_polishing
            else -> null
        }
}

fun mapKeyboardUiState(
    isRecording: Boolean,
    transcriptionPhase: InlineTranscriptionPhase,
    modelBanner: ModelBannerState,
    modelInitFailedMessage: String?,
    llmEnabled: Boolean,
    processingMode: ProcessingMode,
    availableModes: List<ProcessingModeListItem>,
    permissionError: String?,
): KeyboardUiState {
    val voicePanel =
        when {
            permissionError != null -> VoicePanelPhase.Error
            isRecording -> VoicePanelPhase.Recording
            transcriptionPhase is InlineTranscriptionPhase.LoadingModel -> VoicePanelPhase.LoadingModel
            transcriptionPhase is InlineTranscriptionPhase.Transcribing -> VoicePanelPhase.Transcribing
            transcriptionPhase is InlineTranscriptionPhase.Polishing -> VoicePanelPhase.Polishing
            transcriptionPhase is InlineTranscriptionPhase.Error -> VoicePanelPhase.Error
            transcriptionPhase is InlineTranscriptionPhase.LlmError -> VoicePanelPhase.LlmError
            else -> VoicePanelPhase.Idle
        }

    val modelLoadProgress =
        (transcriptionPhase as? InlineTranscriptionPhase.LoadingModel)?.progress

    // Keep the banner tied to the actual model state, not the voice phase: while the model is
    // warming it must stay present across LoadingModel/Transcribing/Polishing so the panel does
    // not reflow ("breathe") once per dictation. It clears only when the model state itself
    // changes (modelBanner becomes None once the model is ready). A permission error replaces the
    // whole panel with error content, so the banner is suppressed only in that case.
    val resolvedModelBanner =
        if (permissionError != null) ModelBannerState.None else modelBanner

    return KeyboardUiState(
        voicePanel = voicePanel,
        modelLoadProgress = modelLoadProgress,
        modelBanner = resolvedModelBanner,
        modelInitFailedMessage = modelInitFailedMessage,
        llmEnabled = llmEnabled,
        processingMode = processingMode,
        availableModes = availableModes,
        errorOverlay = permissionError,
        errorMessage = (transcriptionPhase as? InlineTranscriptionPhase.Error)?.message,
        llmErrorMessage = (transcriptionPhase as? InlineTranscriptionPhase.LlmError)?.message,
        showTypingControls = permissionError == null,
        showRecordingActions = isRecording,
        settingsEnabled = permissionError == null,
    )
}
