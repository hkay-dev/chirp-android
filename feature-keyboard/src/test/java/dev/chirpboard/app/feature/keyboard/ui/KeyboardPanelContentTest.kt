package dev.chirpboard.app.feature.keyboard.ui

import dev.chirpboard.app.feature.keyboard.session.KeyboardOverlayError
import dev.chirpboard.app.feature.keyboard.session.VoicePanelPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardPanelContentTest {
    @Test
    fun `permission overlay wins over every voice phase`() {
        val content =
            resolveKeyboardPanelContent(
                errorOverlay = KeyboardOverlayError("mic denied", showOpenApp = true),
                voicePanel = VoicePanelPhase.Recording,
                errorMessage = "recognition failed",
                llmErrorMessage = "llm failed",
            )

        assertEquals(KeyboardPanelContent.ErrorOverlay("mic denied", showOpenApp = true), content)
        assertEquals(KeyboardPanelContentKind.ErrorOverlay, content.kind())
    }

    @Test
    fun `llm error shows only when phase is LlmError with a message`() {
        val content =
            resolveKeyboardPanelContent(
                errorOverlay = null,
                voicePanel = VoicePanelPhase.LlmError,
                errorMessage = null,
                llmErrorMessage = "llm failed",
            )

        assertEquals(KeyboardPanelContent.LlmError("llm failed"), content)
    }

    @Test
    fun `recognition error shows only when phase is Error with a message`() {
        val content =
            resolveKeyboardPanelContent(
                errorOverlay = null,
                voicePanel = VoicePanelPhase.Error,
                errorMessage = "recognition failed",
                llmErrorMessage = null,
            )

        assertEquals(KeyboardPanelContent.RecognitionError("recognition failed"), content)
    }

    @Test
    fun `error phase without a message falls back to the panel`() {
        val content =
            resolveKeyboardPanelContent(
                errorOverlay = null,
                voicePanel = VoicePanelPhase.Error,
                errorMessage = null,
                llmErrorMessage = null,
            )

        assertEquals(KeyboardPanelContent.Panel, content)
    }

    @Test
    fun `sensitive notice replaces the panel`() {
        // IME-4: the dictation-off notice owns the center panel on password fields.
        val notice =
            resolveKeyboardPanelContent(
                errorOverlay = null,
                voicePanel = VoicePanelPhase.Idle,
                errorMessage = null,
                llmErrorMessage = null,
                sensitiveInputNotice = true,
            )
        assertEquals(KeyboardPanelContent.SensitiveNotice, notice)
    }

    @Test
    fun `sensitive notice outranks stale phase-derived errors`() {
        // IME-4 regression: dictation cannot start in a sensitive field, so an Error/LlmError
        // phase there is always leftover from a previous field — showing it would pair a
        // misleading message with a Retry that is a guaranteed no-op (no commit session).
        val recognitionError =
            resolveKeyboardPanelContent(
                errorOverlay = null,
                voicePanel = VoicePanelPhase.Error,
                errorMessage = "recognition failed",
                llmErrorMessage = null,
                sensitiveInputNotice = true,
            )
        assertEquals(KeyboardPanelContent.SensitiveNotice, recognitionError)

        val llmError =
            resolveKeyboardPanelContent(
                errorOverlay = null,
                voicePanel = VoicePanelPhase.LlmError,
                errorMessage = null,
                llmErrorMessage = "llm failed",
                sensitiveInputNotice = true,
            )
        assertEquals(KeyboardPanelContent.SensitiveNotice, llmError)
    }

    @Test
    fun `every non-error phase resolves to the same stable panel key`() {
        val phases =
            listOf(
                VoicePanelPhase.Idle,
                VoicePanelPhase.Recording,
                VoicePanelPhase.LoadingModel,
                VoicePanelPhase.Transcribing,
                VoicePanelPhase.Polishing,
            )

        val kinds =
            phases.map { phase ->
                resolveKeyboardPanelContent(
                    errorOverlay = null,
                    voicePanel = phase,
                    errorMessage = null,
                    llmErrorMessage = null,
                ).kind()
            }

        assertEquals(listOf(KeyboardPanelContentKind.Panel), kinds.distinct())
    }
}
