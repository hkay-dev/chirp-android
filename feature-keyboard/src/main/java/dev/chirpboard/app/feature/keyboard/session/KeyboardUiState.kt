package dev.chirpboard.app.feature.keyboard.session

import androidx.annotation.StringRes
import dev.chirpboard.app.core.llm.ProcessingMode
import dev.chirpboard.app.core.llm.ProcessingModeDefaults
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

/**
 * A full-panel error overlay (IME-4 / ERR-8). [showOpenApp] distinguishes the mic-permission
 * case — where the only real fix is opening the app to grant RECORD_AUDIO, so the overlay offers
 * an "Open Chirp" action — from session errors (e.g. "input field changed") whose affordance is a
 * plain dismiss.
 */
data class KeyboardOverlayError(
    val message: String,
    val showOpenApp: Boolean = false,
)

/**
 * Whether this banner is an actionable state the user must SEE as a banner (KBD-2).
 *
 * Only [ModelBannerState.NotDownloaded] (user must open the app to download) and
 * [ModelBannerState.InitFailed] (load failed, retry) warrant the explicit text banner. The
 * [ModelBannerState.Initializing] "model is warming into RAM" case is no longer surfaced as a
 * jarring progress-bar banner; it is masked as a subtle shimmer/pulse on the mic affordance via
 * [ModelBannerState.isWarming]. [ModelBannerState.None] needs no banner.
 */
fun ModelBannerState.requiresActionBanner(): Boolean =
    this == ModelBannerState.NotDownloaded || this == ModelBannerState.InitFailed

/**
 * Whether the speech model is warming into memory (KBD-2 / KBD-3) — the masked-load case. Drives
 * the idle mic's shimmer/pulse + "warming" affordance instead of an abrupt progress banner.
 */
fun ModelBannerState.isWarming(): Boolean = this == ModelBannerState.Initializing

/**
 * Resolves the mode an inline keyboard dictation actually uses (PLH-1): the keyboard-scoped
 * default mode preference wins when set and resolvable, otherwise the global processing mode.
 * Custom presets resolve through [availableModes]; the built-in ids resolve even before the
 * selectable-modes flow has emitted.
 */
fun resolveKeyboardSessionMode(
    keyboardDefaultModeId: String?,
    globalMode: ProcessingMode,
    availableModes: List<ProcessingModeListItem>,
): ProcessingMode {
    if (keyboardDefaultModeId.isNullOrBlank()) return globalMode
    val listed = availableModes.firstOrNull { it.id == keyboardDefaultModeId }
    if (listed != null) {
        return ProcessingMode(id = listed.id, displayName = listed.name)
    }
    if (keyboardDefaultModeId in ProcessingModeDefaults.builtInSelectableIds) {
        return ProcessingMode(
            id = keyboardDefaultModeId,
            displayName = ProcessingModeDefaults.displayName(keyboardDefaultModeId),
        )
    }
    return globalMode
}

data class KeyboardUiState(
    val voicePanel: VoicePanelPhase,
    val modelLoadProgress: Float?,
    val modelBanner: ModelBannerState,
    val modelInitFailedMessage: String? = null,
    val llmEnabled: Boolean,
    val processingMode: ProcessingMode,
    val availableModes: List<ProcessingModeListItem> = emptyList(),
    val errorOverlay: KeyboardOverlayError? = null,
    val errorMessage: String? = null,
    val llmErrorMessage: String? = null,
    /**
     * Typing aids (backspace, space, cursor drag, action key) stay available in every state —
     * including password fields and mic-permission errors, where only DICTATION is off (IME-4).
     */
    val showTypingControls: Boolean = true,
    val showRecordingActions: Boolean = false,
    val settingsEnabled: Boolean = true,
    /** Password/blocked field: the center panel shows a neutral "dictation off" notice (IME-4). */
    val sensitiveInputNotice: Boolean = false,
    /**
     * AUD-02 (keyboard half): the live dictation is receiving pure digital silence — the mic
     * is held by another app or the privacy toggle is off. Swaps the "Recording" status label
     * for the "no audio detected" hint; only ever true while [voicePanel] is Recording.
     */
    val silenceHint: Boolean = false,
    /**
     * MIC-014 (keyboard half): the session's active input device disconnected mid-dictation
     * (hot-unplug, Bluetooth drop). Inform-don't-stop: the platform reroutes capture to a
     * fallback mic and the dictation continues, so this only swaps the "Recording" status
     * label for the "mic disconnected" hint; only ever true while [voicePanel] is Recording.
     */
    val deviceLostHint: Boolean = false,
    /** Best-effort live text. Final insertion always comes from the complete saved audio. */
    val partialTranscript: String? = null,
) {
    @StringRes
    fun statusLabelRes(): Int? =
        when {
            voicePanel == VoicePanelPhase.Recording && silenceHint -> R.string.keyboard_status_no_audio
            // MIC-014: silence outranks the disconnect hint — no audio at all is the more
            // actionable signal when both fire on the same unplug.
            voicePanel == VoicePanelPhase.Recording && deviceLostHint -> R.string.keyboard_status_device_lost
            voicePanel == VoicePanelPhase.Recording -> R.string.keyboard_status_recording
            voicePanel == VoicePanelPhase.LoadingModel -> R.string.keyboard_loading_speech_model
            voicePanel == VoicePanelPhase.Transcribing -> R.string.keyboard_transcribing
            voicePanel == VoicePanelPhase.Polishing -> R.string.keyboard_polishing
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
    overlayError: KeyboardOverlayError?,
    sensitiveInput: Boolean = false,
    silenceDetected: Boolean = false,
    deviceLost: Boolean = false,
    partialTranscript: String? = null,
): KeyboardUiState {
    val voicePanel =
        when {
            overlayError != null -> VoicePanelPhase.Error
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
    // changes (modelBanner becomes None once the model is ready). An error overlay replaces the
    // whole panel with error content, and a sensitive field shows only the dictation-off notice,
    // so the banner is suppressed in those cases.
    val resolvedModelBanner =
        if (overlayError != null || sensitiveInput) ModelBannerState.None else modelBanner

    return KeyboardUiState(
        voicePanel = voicePanel,
        modelLoadProgress = modelLoadProgress,
        modelBanner = resolvedModelBanner,
        modelInitFailedMessage = modelInitFailedMessage,
        llmEnabled = llmEnabled,
        processingMode = processingMode,
        availableModes = availableModes,
        errorOverlay = overlayError,
        errorMessage = (transcriptionPhase as? InlineTranscriptionPhase.Error)?.message,
        llmErrorMessage = (transcriptionPhase as? InlineTranscriptionPhase.LlmError)?.message,
        showTypingControls = true,
        showRecordingActions = isRecording,
        // AI enhancement settings are dictation-scoped: pointless while dictation itself is
        // unavailable (error overlay or a sensitive field).
        settingsEnabled = overlayError == null && !sensitiveInput,
        sensitiveInputNotice = sensitiveInput,
        // AUD-02: gate on the LIVE recording phase so a stale silence flag (or one arriving
        // while an error overlay replaced the panel) can never show the hint out of context.
        silenceHint = voicePanel == VoicePanelPhase.Recording && silenceDetected,
        // MIC-014: same live-phase gating for the device-lost hint, so it dies with the
        // session instead of leaking into transcription or the next dictation.
        deviceLostHint = voicePanel == VoicePanelPhase.Recording && deviceLost,
        partialTranscript = partialTranscript?.takeIf { voicePanel == VoicePanelPhase.Recording },
    )
}
