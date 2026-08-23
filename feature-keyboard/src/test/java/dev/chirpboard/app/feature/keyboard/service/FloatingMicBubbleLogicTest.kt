package dev.chirpboard.app.feature.keyboard.service

import dev.chirpboard.app.core.llm.ProcessingMode
import dev.chirpboard.app.feature.keyboard.session.KeyboardUiState
import dev.chirpboard.app.feature.keyboard.session.ModelBannerState
import dev.chirpboard.app.feature.keyboard.session.VoicePanelPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingMicBubbleLogicTest {
    private fun uiState(voicePanel: VoicePanelPhase): KeyboardUiState =
        KeyboardUiState(
            voicePanel = voicePanel,
            modelLoadProgress = null,
            modelBanner = ModelBannerState.None,
            llmEnabled = true,
            processingMode = ProcessingMode(id = "proofread", displayName = "Proofread"),
        )

    @Test
    fun recordingMapsToRecording() {
        assertEquals(
            FloatingBubblePhase.Recording,
            floatingBubblePhaseFor(uiState(VoicePanelPhase.Recording)),
        )
    }

    @Test
    fun workingPhasesMapToBusy() {
        listOf(
            VoicePanelPhase.LoadingModel,
            VoicePanelPhase.Transcribing,
            VoicePanelPhase.Polishing,
        ).forEach { phase ->
            assertEquals(FloatingBubblePhase.Busy, floatingBubblePhaseFor(uiState(phase)))
        }
    }

    @Test
    fun idleAndErrorPhasesMapToIdle() {
        listOf(
            VoicePanelPhase.Idle,
            VoicePanelPhase.Error,
            VoicePanelPhase.LlmError,
        ).forEach { phase ->
            assertEquals(FloatingBubblePhase.Idle, floatingBubblePhaseFor(uiState(phase)))
        }
    }

    @Test
    fun showsOnlyWithEveryConditionMet() {
        assertTrue(
            shouldShowFloatingBubble(
                enabled = true,
                canDrawOverlays = true,
                windowShown = true,
                inputViewActive = true,
                sensitiveInput = false,
            ),
        )
    }

    @Test
    fun hiddenWhenDisabledOrUngrantedOrDormant() {
        assertFalse(
            shouldShowFloatingBubble(
                enabled = false,
                canDrawOverlays = true,
                windowShown = true,
                inputViewActive = true,
                sensitiveInput = false,
            ),
        )
        assertFalse(
            shouldShowFloatingBubble(
                enabled = true,
                canDrawOverlays = false,
                windowShown = true,
                inputViewActive = true,
                sensitiveInput = false,
            ),
        )
        assertFalse(
            shouldShowFloatingBubble(
                enabled = true,
                canDrawOverlays = true,
                windowShown = false,
                inputViewActive = true,
                sensitiveInput = false,
            ),
        )
        assertFalse(
            shouldShowFloatingBubble(
                enabled = true,
                canDrawOverlays = true,
                windowShown = true,
                inputViewActive = false,
                sensitiveInput = false,
            ),
        )
    }

    @Test
    fun hiddenOnSensitiveInput() {
        assertFalse(
            shouldShowFloatingBubble(
                enabled = true,
                canDrawOverlays = true,
                windowShown = true,
                inputViewActive = true,
                sensitiveInput = true,
            ),
        )
    }
}
