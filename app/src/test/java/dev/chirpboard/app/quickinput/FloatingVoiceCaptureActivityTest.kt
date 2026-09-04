package dev.chirpboard.app.quickinput

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FloatingVoiceCaptureActivityTest {
    @Test
    fun `successful recognition returns the first nonblank result`() {
        assertEquals(
            "spoken words",
            floatingVoiceCaptureResultText(
                resultCode = Activity.RESULT_OK,
                results = listOf("  ", " spoken words ", "alternate"),
            ),
        )
    }

    @Test
    fun `cancelled recognition does not copy text`() {
        assertNull(
            floatingVoiceCaptureResultText(
                resultCode = Activity.RESULT_CANCELED,
                results = listOf("spoken words"),
            ),
        )
    }

    @Test
    fun `missing recognition results do not copy text`() {
        assertNull(floatingVoiceCaptureResultText(Activity.RESULT_OK, null))
    }

    @Test
    fun `blank recognition results do not copy text`() {
        assertNull(floatingVoiceCaptureResultText(Activity.RESULT_OK, listOf("", "   ")))
    }

    @Test
    fun `review copy keeps edited whitespace emoji and newlines`() {
        val edited = "  Hello 👋\nSecond line  "

        assertEquals(edited, floatingVoiceReviewCopyText(edited, copyStarted = false))
    }

    @Test
    fun `blank review cannot replace the clipboard`() {
        assertNull(floatingVoiceReviewCopyText(" \n ", copyStarted = false))
    }

    @Test
    fun `second review confirmation cannot copy twice`() {
        assertNull(floatingVoiceReviewCopyText("spoken words", copyStarted = true))
    }

    @Test
    fun `saved review phase restores without relaunching recognition`() {
        assertEquals(
            FloatingVoiceCapturePhase.Reviewing,
            floatingVoiceCapturePhase(FloatingVoiceCapturePhase.Reviewing.name),
        )
    }

    @Test
    fun `missing saved phase falls back to awaiting recognition`() {
        assertEquals(
            FloatingVoiceCapturePhase.AwaitingRecognition,
            floatingVoiceCapturePhase(null),
        )
    }
}
