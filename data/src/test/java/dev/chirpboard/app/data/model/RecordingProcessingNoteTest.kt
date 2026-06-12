package dev.chirpboard.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I18N-06: the persisted marker strings are a frozen machine contract. These tests pin the
 * classifier against the exact strings historical rows carry; if any assertion here has to
 * change, on-disk rows from older builds will stop classifying.
 */
class RecordingProcessingNoteTest {
    @Test
    fun `model-unavailable markers classify as waiting for model`() {
        listOf(
            "Model not downloaded. Please download the speech recognition model in Settings.",
            "Failed to initialize speech recognition model",
            "Speech model unavailable: missing tokens.txt",
            "Recognizer not ready after download",
        ).forEach { message ->
            assertEquals(
                message,
                RecordingProcessingNoteKind.WAITING_FOR_MODEL,
                classifyRecordingProcessingNote(message),
            )
            assertTrue(message, isWaitingForSpeechModel(message))
        }
    }

    @Test
    fun `recovery markers classify by prefix`() {
        assertEquals(
            RecordingProcessingNoteKind.STALE_RECOVERED,
            classifyRecordingProcessingNote("recoverable_stale_transcribing:Recovered stale transcribing state"),
        )
        assertEquals(
            RecordingProcessingNoteKind.STALE_RECOVERED,
            classifyRecordingProcessingNote("recoverable_stale_enhancing:Enhancement stalled; you can retry"),
        )
        assertEquals(
            RecordingProcessingNoteKind.QUEUE_HANDOFF,
            classifyRecordingProcessingNote("recoverable_queue_handoff:enhancement enqueue failed. Cause: boom"),
        )
        assertEquals(
            RecordingProcessingNoteKind.MANUAL_RECOVERY,
            classifyRecordingProcessingNote("manual_recovery:user_retry|attemptAt=1718100000000"),
        )
    }

    @Test
    fun `raw exception text and blanks classify as other`() {
        assertEquals(
            RecordingProcessingNoteKind.OTHER,
            classifyRecordingProcessingNote("java.io.IOException: write failed: ENOSPC"),
        )
        assertEquals(RecordingProcessingNoteKind.OTHER, classifyRecordingProcessingNote(null))
        assertEquals(RecordingProcessingNoteKind.OTHER, classifyRecordingProcessingNote(""))
        assertFalse(isWaitingForSpeechModel("java.io.IOException: boom"))
        assertFalse(isWaitingForSpeechModel(null))
    }
}
