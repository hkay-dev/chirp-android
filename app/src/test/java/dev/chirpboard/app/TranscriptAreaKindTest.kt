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
                hasError = false,
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
                hasError = false,
                modelState = VoiceRecognitionModelState.Ready,
                preRollComplete = true,
                isRecording = true,
                isProcessing = true,
            ),
        )
    }

    @Test
    fun `a terminal error owns the area regardless of any other state`() {
        // ERR-9/ERR-23/ERR-27: failures must be explained in the dialog instead of a
        // silent close, so the error kind takes precedence over everything.
        assertEquals(
            TranscriptAreaKind.Error,
            transcriptAreaKind(
                hasText = true,
                hasError = true,
                modelState = VoiceRecognitionModelState.Ready,
                preRollComplete = true,
                isRecording = true,
                isProcessing = true,
            ),
        )
        assertEquals(
            TranscriptAreaKind.Error,
            transcriptAreaKind(
                hasText = false,
                hasError = true,
                modelState = VoiceRecognitionModelState.Initializing,
                preRollComplete = false,
                isRecording = false,
                isProcessing = false,
            ),
        )
    }

    @Test
    fun `model loading shows when there is no text and model is initializing`() {
        assertEquals(
            TranscriptAreaKind.ModelLoading,
            transcriptAreaKind(
                hasText = false,
                hasError = false,
                modelState = VoiceRecognitionModelState.Initializing,
                preRollComplete = true,
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
                hasError = false,
                modelState = VoiceRecognitionModelState.Unavailable,
                preRollComplete = true,
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
                hasError = false,
                modelState = VoiceRecognitionModelState.Ready,
                preRollComplete = true,
                isRecording = true,
                isProcessing = false,
            ),
        )
    }

    @Test
    fun `processing shows a textual status instead of an empty area`() {
        // A11Y-8 (intentional change): the post-stop phase previously rendered nothing,
        // leaving the busiest moment of the flow with no visual or accessible status.
        assertEquals(
            TranscriptAreaKind.Processing,
            transcriptAreaKind(
                hasText = false,
                hasError = false,
                modelState = VoiceRecognitionModelState.Ready,
                preRollComplete = true,
                isRecording = true,
                isProcessing = true,
            ),
        )
    }

    @Test
    fun `calm visual beat runs while capture and model warmup are already active`() {
        // The visual beat is presentation only. It wins briefly even though the microphone has
        // started and the recognizer is still loading.
        assertEquals(
            TranscriptAreaKind.Ready,
            transcriptAreaKind(
                hasText = false,
                hasError = false,
                modelState = VoiceRecognitionModelState.Initializing,
                preRollComplete = false,
                isRecording = true,
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
                hasError = false,
                modelState = VoiceRecognitionModelState.Ready,
                preRollComplete = true,
                isRecording = false,
                isProcessing = false,
            ),
        )
    }
}
