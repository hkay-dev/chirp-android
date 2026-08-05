package dev.chirpboard.app.feature.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickInputResultNotificationTest {
    @Test
    fun `blank raw text does not create notification content`() {
        assertNull(quickInputNotificationContent("   ", "AI text"))
    }

    @Test
    fun `content trims text and keeps a distinct AI result`() {
        assertEquals(
            QuickInputNotificationContent(
                rawText = "original words",
                processedText = "Polished words.",
            ),
            quickInputNotificationContent("  original words  ", "  Polished words.  "),
        )
    }

    @Test
    fun `duplicate AI result is omitted`() {
        assertEquals(
            QuickInputNotificationContent(
                rawText = "same words",
                processedText = null,
            ),
            quickInputNotificationContent("same words", " same words "),
        )
    }

    @Test
    fun `expanded content puts AI result above original`() {
        val content = QuickInputNotificationContent("original words", "Polished words.")

        assertEquals(
            "AI result\nPolished words.\n\nOriginal\noriginal words",
            quickInputNotificationExpandedText(content, rawLabel = "Original", aiLabel = "AI result"),
        )
    }

    @Test
    fun `raw-only expanded content has no label noise`() {
        val content = QuickInputNotificationContent("original words", null)

        assertEquals(
            "original words",
            quickInputNotificationExpandedText(content, rawLabel = "Original", aiLabel = "AI result"),
        )
    }

    @Test
    fun `latest result notification expires after thirty seconds`() {
        assertEquals(30_000L, QUICK_INPUT_RESULT_TIMEOUT_MS)
    }

    @Test
    fun `notification paste without a captured session targets the active editor`() {
        var activeEditorText: String? = null
        val handler =
            object : QuickInputPasteHandler {
                override fun requestPaste(
                    sessionId: Long,
                    useProcessedText: Boolean,
                ): Boolean = false

                override fun requestPasteIntoActiveEditor(text: String): Boolean {
                    activeEditorText = text
                    return true
                }
            }

        assertTrue(
            requestQuickInputPaste(
                pasteHandler = handler,
                sessionId = null,
                useProcessedText = true,
                text = "Polished words.",
            ),
        )
        assertEquals("Polished words.", activeEditorText)
    }
}
