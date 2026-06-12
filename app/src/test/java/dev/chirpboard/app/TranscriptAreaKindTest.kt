package dev.chirpboard.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the content-kind selection that keys the transcript-area crossfade (UI-16 / CMP-11).
 * The crossfade keys on the kind, not the streaming text, so streamed words update a plain
 * Text in place instead of ghost-crossfading the whole paragraph against itself per token.
 */
class TranscriptAreaKindTest {
    @Test
    fun `any text wins over every other state so streaming stays in the transcript kind`() {
        // Same kind regardless of model/recording state, so a growing partial transcript
        // never restarts the crossfade once the first word arrives.
        assertEquals(
            TranscriptAreaKind.Transcript,
            transcriptAreaKind(
                hasText = true,
                modelState = VoiceRecognitionModelState.Initializing,
                preRollComplete = false,
                isRecording = false,
                isProcessing = false,
            ),
        )
        assertEquals(
            TranscriptAreaKind.Transcript,
            transcriptAreaKind(
                hasText = true,
                modelState = VoiceRecognitionModelState.Ready,
                preRollComplete = true,
                isRecording = true,
                isProcessing = true,
            ),
        )
    }

    @Test
    fun `model loading shows when there is no text and model is initializing`() {
        assertEquals(
            TranscriptAreaKind.ModelLoading,
            transcriptAreaKind(
                hasText = false,
                modelState = VoiceRecognitionModelState.Initializing,
                preRollComplete = false,
                isRecording = false,
                isProcessing = false,
            ),
        )
    }

    @Test
    fun `model unavailable shows when there is no text and model failed`() {
        assertEquals(
            TranscriptAreaKind.ModelUnavailable,
            transcriptAreaKind(
                hasText = false,
                modelState = VoiceRecognitionModelState.Unavailable,
                preRollComplete = false,
                isRecording = true,
                isProcessing = false,
            ),
        )
    }

    @Test
    fun `timer shows when recording with a ready model and no text yet`() {
        assertEquals(
            TranscriptAreaKind.Timer,
            transcriptAreaKind(
                hasText = false,
                modelState = VoiceRecognitionModelState.Ready,
                preRollComplete = true,
                isRecording = true,
                isProcessing = false,
            ),
        )
    }

    @Test
    fun `processing hides the timer so it does not flash during transcription`() {
        assertEquals(
            TranscriptAreaKind.Empty,
            transcriptAreaKind(
                hasText = false,
                modelState = VoiceRecognitionModelState.Ready,
                preRollComplete = true,
                isRecording = true,
                isProcessing = true,
            ),
        )
    }

    @Test
    fun `ready model before capture shows the calm pre-roll beat`() {
        // Model is Ready but the pre-roll beat has not elapsed and capture has not begun: the calm
        // "Ready to listen" frame is shown instead of jumping straight to the timer (DLG-4/LOAD-5).
        assertEquals(
            TranscriptAreaKind.Ready,
            transcriptAreaKind(
                hasText = false,
                modelState = VoiceRecognitionModelState.Ready,
                preRollComplete = false,
                isRecording = false,
                isProcessing = false,
            ),
        )
    }

    @Test
    fun `idle ready state after pre-roll with no text and not recording is empty`() {
        // Pre-roll done, model ready, not yet recording (the brief gap before Starting lands):
        // empty rather than re-showing the pre-roll copy.
        assertEquals(
            TranscriptAreaKind.Empty,
            transcriptAreaKind(
                hasText = false,
                modelState = VoiceRecognitionModelState.Ready,
                preRollComplete = true,
                isRecording = false,
                isProcessing = false,
            ),
        )
    }
}
