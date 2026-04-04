package dev.chirpboard.app.feature.keyboard.service

import dev.chirpboard.app.data.model.RecordingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [buildKeyboardPersistencePlan].
 *
 * Covers the save-on-failure and teardown paths introduced to fix:
 * - IME teardown dropping in-progress recordings (rawText = null, errorMessage set)
 * - Transcription failure discarding buffered audio (rawText = null, errorMessage set)
 * - The happy path where transcription succeeded (rawText non-null, no error)
 */
class KeyboardPersistencePlanTest {

    // --- title derivation ---

    @Test
    fun `title is derived from rawText when present`() {
        val plan = buildKeyboardPersistencePlan(
            rawText = "hello world",
            processedText = null,
            errorMessage = null
        )
        assertEquals("hello world", plan.title)
    }

    @Test
    fun `title is truncated to 50 characters`() {
        val longText = "a".repeat(80)
        val plan = buildKeyboardPersistencePlan(
            rawText = longText,
            processedText = null,
            errorMessage = null
        )
        assertEquals(50, plan.title.length)
    }

    @Test
    fun `title falls back to errorMessage when rawText is null`() {
        val plan = buildKeyboardPersistencePlan(
            rawText = null,
            processedText = null,
            errorMessage = "Recognizer not ready"
        )
        assertEquals("Recognizer not ready", plan.title)
    }

    @Test
    fun `title falls back to default when both rawText and errorMessage are null`() {
        val plan = buildKeyboardPersistencePlan(
            rawText = null,
            processedText = null,
            errorMessage = null
        )
        assertEquals("Keyboard recording", plan.title)
    }

    @Test
    fun `blank rawText is treated as absent and falls back to errorMessage for title`() {
        val plan = buildKeyboardPersistencePlan(
            rawText = "   ",
            processedText = null,
            errorMessage = "Transcription engine failed"
        )
        assertEquals("Transcription engine failed", plan.title)
    }

    // --- status ---

    @Test
    fun `status is COMPLETED when there is no error`() {
        val plan = buildKeyboardPersistencePlan(
            rawText = "hello",
            processedText = null,
            errorMessage = null
        )
        assertEquals(RecordingStatus.COMPLETED, plan.status)
    }

    @Test
    fun `status is FAILED when errorMessage is present`() {
        val plan = buildKeyboardPersistencePlan(
            rawText = null,
            processedText = null,
            errorMessage = "Recording stopped when the keyboard closed before transcription finished"
        )
        assertEquals(RecordingStatus.FAILED, plan.status)
    }

    @Test
    fun `status is FAILED when rawText is blank and errorMessage is present`() {
        val plan = buildKeyboardPersistencePlan(
            rawText = "  ",
            processedText = null,
            errorMessage = "Transcription engine failed: decoder crashed"
        )
        assertEquals(RecordingStatus.FAILED, plan.status)
    }

    // --- rawText normalization ---

    @Test
    fun `rawText is preserved when non-blank`() {
        val plan = buildKeyboardPersistencePlan(
            rawText = "voice memo",
            processedText = null,
            errorMessage = null
        )
        assertEquals("voice memo", plan.rawText)
    }

    @Test
    fun `rawText is null when input rawText is blank`() {
        val plan = buildKeyboardPersistencePlan(
            rawText = "   ",
            processedText = "polished",
            errorMessage = null
        )
        assertNull(plan.rawText)
    }

    @Test
    fun `rawText is null in teardown path where no transcription occurred`() {
        val plan = buildKeyboardPersistencePlan(
            rawText = null,
            processedText = null,
            errorMessage = "Recording stopped because the keyboard service was destroyed"
        )
        assertNull(plan.rawText)
    }

    // --- processedText ---

    @Test
    fun `processedText is preserved when rawText is non-blank`() {
        val plan = buildKeyboardPersistencePlan(
            rawText = "hello",
            processedText = "Hello.",
            errorMessage = null
        )
        assertEquals("Hello.", plan.processedText)
    }

    @Test
    fun `processedText is dropped when rawText normalises to null`() {
        // Without a raw transcript there is nothing to attach processed text to.
        val plan = buildKeyboardPersistencePlan(
            rawText = null,
            processedText = "should be dropped",
            errorMessage = "Recognizer not ready"
        )
        assertNull(plan.processedText)
    }

    // --- errorMessage ---

    @Test
    fun `errorMessage is preserved on failure`() {
        val plan = buildKeyboardPersistencePlan(
            rawText = null,
            processedText = null,
            errorMessage = "Transcription engine failed: decoder crashed"
        )
        assertEquals("Transcription engine failed: decoder crashed", plan.errorMessage)
    }

    @Test
    fun `errorMessage is null on success`() {
        val plan = buildKeyboardPersistencePlan(
            rawText = "hello",
            processedText = null,
            errorMessage = null
        )
        assertNull(plan.errorMessage)
    }

    @Test
    fun `blank errorMessage is normalised to null`() {
        val plan = buildKeyboardPersistencePlan(
            rawText = "hello",
            processedText = null,
            errorMessage = "   "
        )
        assertNull(plan.errorMessage)
    }
}
