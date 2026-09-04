package dev.chirpboard.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecognitionFloatingReviewTest {
    @Test
    fun `successful auto copy closes when review is off`() {
        assertFalse(
            shouldShowFloatingReviewEditor(
                reviewEnabled = false,
                copySucceeded = true,
            ),
        )
    }

    @Test
    fun `review switch opens editor after successful auto copy`() {
        assertTrue(
            shouldShowFloatingReviewEditor(
                reviewEnabled = true,
                copySucceeded = true,
            ),
        )
    }

    @Test
    fun `copy failure keeps an editor available for retry`() {
        assertTrue(
            shouldShowFloatingReviewEditor(
                reviewEnabled = false,
                copySucceeded = false,
            ),
        )
    }
}
