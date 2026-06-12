package dev.chirpboard.app.feature.keyboard.session

import dev.chirpboard.app.core.transcription.InlineTranscriptionPhase
import dev.chirpboard.app.core.llm.ProcessingMode
import dev.chirpboard.app.core.llm.ProcessingModeListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                overlayError = null,
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
                overlayError = null,
            )
        assertEquals(VoicePanelPhase.Recording, state.voicePanel)
        assertTrue(state.showRecordingActions)
    }

    @Test
    fun `silence while recording surfaces the no-audio hint`() {
        // AUD-02 keyboard half: a silenced mic mid-dictation must be visible in the panel.
        val state =
            mapKeyboardUiState(
                isRecording = true,
                transcriptionPhase = InlineTranscriptionPhase.Idle,
                modelBanner = ModelBannerState.None,
                modelInitFailedMessage = null,
                llmEnabled = true,
                processingMode = ProcessingMode.Proofread,
                availableModes = emptyList(),
                overlayError = null,
                silenceDetected = true,
            )
        assertTrue(state.silenceHint)
        assertEquals(
            dev.chirpboard.app.feature.keyboard.R.string.keyboard_status_no_audio,
            state.statusLabelRes(),
        )
    }

    @Test
    fun `silence flag is ignored outside a live recording`() {
        // A stale silence flag (session ended mid-silence) must never show the hint while
        // idle, transcribing, or behind an error overlay.
        val idle =
            mapKeyboardUiState(
                isRecording = false,
                transcriptionPhase = InlineTranscriptionPhase.Idle,
                modelBanner = ModelBannerState.None,
                modelInitFailedMessage = null,
                llmEnabled = true,
                processingMode = ProcessingMode.Proofread,
                availableModes = emptyList(),
                overlayError = null,
                silenceDetected = true,
            )
        assertFalse(idle.silenceHint)

        val overlay =
            mapKeyboardUiState(
                isRecording = true,
                transcriptionPhase = InlineTranscriptionPhase.Idle,
                modelBanner = ModelBannerState.None,
                modelInitFailedMessage = null,
                llmEnabled = true,
                processingMode = ProcessingMode.Proofread,
                availableModes = emptyList(),
                overlayError = KeyboardOverlayError("error"),
                silenceDetected = true,
            )
        assertFalse(overlay.silenceHint)
    }

    @Test
    fun `device lost while recording surfaces the disconnect hint`() {
        // MIC-014 keyboard half: a hot-unplugged active mic mid-dictation must be visible
        // in the panel (inform, don't stop — the platform reroutes and capture continues).
        val state =
            mapKeyboardUiState(
                isRecording = true,
                transcriptionPhase = InlineTranscriptionPhase.Idle,
                modelBanner = ModelBannerState.None,
                modelInitFailedMessage = null,
                llmEnabled = true,
                processingMode = ProcessingMode.Proofread,
                availableModes = emptyList(),
                overlayError = null,
                deviceLost = true,
            )
        assertTrue(state.deviceLostHint)
        assertEquals(
            dev.chirpboard.app.feature.keyboard.R.string.keyboard_status_device_lost,
            state.statusLabelRes(),
        )
    }

    @Test
    fun `device lost flag is ignored outside a live recording and outranked by silence`() {
        // A stale device-lost flag must never show the hint once the session ended, and
        // when silence and a disconnect fire on the same unplug the no-audio hint wins.
        val idle =
            mapKeyboardUiState(
                isRecording = false,
                transcriptionPhase = InlineTranscriptionPhase.Idle,
                modelBanner = ModelBannerState.None,
                modelInitFailedMessage = null,
                llmEnabled = true,
                processingMode = ProcessingMode.Proofread,
                availableModes = emptyList(),
                overlayError = null,
                deviceLost = true,
            )
        assertFalse(idle.deviceLostHint)

        val silencedToo =
            mapKeyboardUiState(
                isRecording = true,
                transcriptionPhase = InlineTranscriptionPhase.Idle,
                modelBanner = ModelBannerState.None,
                modelInitFailedMessage = null,
                llmEnabled = true,
                processingMode = ProcessingMode.Proofread,
                availableModes = emptyList(),
                overlayError = null,
                silenceDetected = true,
                deviceLost = true,
            )
        assertEquals(
            dev.chirpboard.app.feature.keyboard.R.string.keyboard_status_no_audio,
            silencedToo.statusLabelRes(),
        )
    }

    @Test
    fun `recording without silence keeps the recording status label`() {
        val state =
            mapKeyboardUiState(
                isRecording = true,
                transcriptionPhase = InlineTranscriptionPhase.Idle,
                modelBanner = ModelBannerState.None,
                modelInitFailedMessage = null,
                llmEnabled = true,
                processingMode = ProcessingMode.Proofread,
                availableModes = emptyList(),
                overlayError = null,
                silenceDetected = false,
            )
        assertFalse(state.silenceHint)
        assertEquals(
            dev.chirpboard.app.feature.keyboard.R.string.keyboard_status_recording,
            state.statusLabelRes(),
        )
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
                    overlayError = null,
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
                overlayError = null,
            )
        assertEquals(VoicePanelPhase.Recording, state.voicePanel)
        assertEquals(ModelBannerState.Initializing, state.modelBanner)
    }

    @Test
    fun `permission error suppresses the model banner but keeps typing controls`() {
        // IME-4: backspace/space still work without the mic; only dictation is unavailable.
        val state =
            mapKeyboardUiState(
                isRecording = false,
                transcriptionPhase = InlineTranscriptionPhase.Idle,
                modelBanner = ModelBannerState.Initializing,
                modelInitFailedMessage = null,
                llmEnabled = true,
                processingMode = ProcessingMode.Proofread,
                availableModes = emptyList(),
                overlayError = KeyboardOverlayError("Microphone permission required", showOpenApp = true),
            )
        assertEquals(ModelBannerState.None, state.modelBanner)
        assertEquals(VoicePanelPhase.Error, state.voicePanel)
        assertTrue(state.showTypingControls)
        assertFalse(state.settingsEnabled)
        assertTrue(state.errorOverlay?.showOpenApp == true)
    }

    @Test
    fun `sensitive input shows notice with typing controls available`() {
        // IME-4: password fields keep every typing aid; the center panel shows a neutral notice.
        val state =
            mapKeyboardUiState(
                isRecording = false,
                transcriptionPhase = InlineTranscriptionPhase.Idle,
                modelBanner = ModelBannerState.Initializing,
                modelInitFailedMessage = null,
                llmEnabled = true,
                processingMode = ProcessingMode.Proofread,
                availableModes = emptyList(),
                overlayError = null,
                sensitiveInput = true,
            )
        assertTrue(state.sensitiveInputNotice)
        assertTrue(state.showTypingControls)
        assertFalse(state.settingsEnabled)
        assertEquals(ModelBannerState.None, state.modelBanner)
    }

    @Test
    fun `only NotDownloaded and InitFailed require the action banner`() {
        // KBD-2: the warming (Initializing) state is masked on the mic, never shown as a banner.
        assertFalse(ModelBannerState.None.requiresActionBanner())
        assertFalse(ModelBannerState.Initializing.requiresActionBanner())
        assertTrue(ModelBannerState.NotDownloaded.requiresActionBanner())
        assertTrue(ModelBannerState.InitFailed.requiresActionBanner())
    }

    @Test
    fun `only Initializing is the masked warming state`() {
        // KBD-2/KBD-3: warming drives the mic shimmer/pulse; nothing else does.
        assertTrue(ModelBannerState.Initializing.isWarming())
        assertFalse(ModelBannerState.None.isWarming())
        assertFalse(ModelBannerState.NotDownloaded.isWarming())
        assertFalse(ModelBannerState.InitFailed.isWarming())
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
                overlayError = null,
            )
        assertEquals(VoicePanelPhase.LoadingModel, state.voicePanel)
    }

    @Test
    fun `session mode falls back to global when no keyboard default is set`() {
        // PLH-1: null/blank keyboard default means "use global setting".
        val global = ProcessingMode("formal", "Formal")
        assertEquals(global, resolveKeyboardSessionMode(null, global, emptyList()))
        assertEquals(global, resolveKeyboardSessionMode("", global, emptyList()))
    }

    @Test
    fun `session mode prefers the keyboard default over the global mode`() {
        val global = ProcessingMode("proofread", "Proofread")
        val modes = listOf(ProcessingModeListItem("email", "Email"), ProcessingModeListItem("code", "Code"))

        val resolved = resolveKeyboardSessionMode("email", global, modes)

        assertEquals("email", resolved.id)
        assertEquals("Email", resolved.displayName)
    }

    @Test
    fun `built-in keyboard default resolves before selectable modes load`() {
        val global = ProcessingMode("proofread", "Proofread")

        val resolved = resolveKeyboardSessionMode("casual", global, emptyList())

        assertEquals("casual", resolved.id)
    }

    @Test
    fun `unresolvable keyboard default falls back to global`() {
        // A stale custom-preset id (deleted preset) must not break dictation.
        val global = ProcessingMode("proofread", "Proofread")

        val resolved = resolveKeyboardSessionMode("deleted-preset", global, emptyList())

        assertEquals(global, resolved)
    }
}
