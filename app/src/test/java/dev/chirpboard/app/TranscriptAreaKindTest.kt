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
                isRecording = false,
                isProcessing = false,
            ),
        )
        assertEquals(
            TranscriptAreaKind.Transcript,
            transcriptAreaKind(
                hasText = true,
                modelState = VoiceRecognitionModelState.Ready,
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
                isRecording = true,
                isProcessing = true,
            ),
        )
    }

    @Test
    fun `idle ready state with no text is empty`() {
        assertEquals(
            TranscriptAreaKind.Empty,
            transcriptAreaKind(
                hasText = false,
                modelState = VoiceRecognitionModelState.Ready,
                isRecording = false,
                isProcessing = false,
            ),
        )
    }
}
