package dev.chirpboard.app.feature.studio

import dev.chirpboard.app.feature.llm.client.TranscriptPassageAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptCorrectionWorkflowTest {
    @Test
    fun `analyzeTranscriptCorrectionPromotion returns single replacement phrase`() {
        val promotion =
            analyzeTranscriptCorrectionPromotion(
                sourceText = "please call Jon tomorrow morning",
                correctedText = "please call John tomorrow morning",
            )

        assertEquals(TranscriptCorrectionPromotion(original = "Jon", replacement = "John"), promotion)
    }

    @Test
    fun `analyzeTranscriptCorrectionPromotion rejects multi change edits`() {
        val promotion =
            analyzeTranscriptCorrectionPromotion(
                sourceText = "alpha beta gamma delta",
                correctedText = "alpha bravo gamma echo",
            )

        assertNull(promotion)
    }

    @Test
    fun `enterTranscriptEditMode clears active segment and disables transcript interactions`() {
        val state =
            ProcessingStudioState(
                activeTranscriptSegmentIndex = 4,
                effectiveTranscriptText = "hello world",
            )

        val editingState = state.enterTranscriptEditMode()

        assertTrue(editingState.isEditingTranscript)
        assertEquals("hello world", editingState.transcriptDraft)
        assertEquals(-1, editingState.activeTranscriptSegmentIndex)
        assertFalse(editingState.canUseTranscriptInteractions())
    }

    @Test
    fun `enterTranscriptSelectionMode clears active segment and disables transcript interactions`() {
        val state =
            ProcessingStudioState(
                activeTranscriptSegmentIndex = 4,
                renderedTranscriptText = "hello world",
            )

        val selectionState = state.enterTranscriptSelectionMode()

        assertTrue(selectionState.isSelectingTranscript)
        assertEquals(-1, selectionState.activeTranscriptSegmentIndex)
        assertFalse(selectionState.canUseTranscriptInteractions())
    }

    @Test
    fun `exitTranscriptSelectionMode clears scoped selection state`() {
        val state =
            ProcessingStudioState(
                isSelectingTranscript = true,
                renderedTranscriptText = "hello world",
                selectedTranscriptPassage = "hello",
                transcriptSelectionActionInFlight = TranscriptPassageAction.SUMMARIZE,
                transcriptSelectionResult =
                    TranscriptSelectionResult(
                        action = TranscriptPassageAction.SUMMARIZE,
                        text = "Brief summary",
                    ),
            )

        val clearedState = state.exitTranscriptSelectionMode()

        assertFalse(clearedState.isSelectingTranscript)
        assertEquals("", clearedState.selectedTranscriptPassage)
        assertNull(clearedState.transcriptSelectionActionInFlight)
        assertNull(clearedState.transcriptSelectionResult)
    }

    @Test
    fun `updateTranscriptSelection clears stale result when selection changes`() {
        val state =
            ProcessingStudioState(
                isSelectingTranscript = true,
                renderedTranscriptText = "alpha beta gamma",
                selectedTranscriptPassage = "alpha",
                transcriptSelectionActionInFlight = TranscriptPassageAction.EXPLAIN,
                transcriptSelectionResult =
                    TranscriptSelectionResult(
                        action = TranscriptPassageAction.EXPLAIN,
                        text = "Old result",
                    ),
            )

        val updatedState = state.updateTranscriptSelection("beta")

        assertEquals("beta", updatedState.selectedTranscriptPassage)
        assertNull(updatedState.transcriptSelectionActionInFlight)
        assertNull(updatedState.transcriptSelectionResult)
    }

    @Test
    fun `untimed transcript text can still enter selection mode`() {
        val transcript = ProcessingStudioTranscript.Untimed(text = "hello untimed world")
        val state =
            ProcessingStudioState(
                transcript = transcript,
                renderedTranscriptText = transcript.renderedText(),
            )

        assertTrue(state.canEnterTranscriptSelectionMode())
    }

    @Test
    fun `matchesTranscriptSelectionRequest rejects stale transcript text`() {
        val state =
            ProcessingStudioState(
                isSelectingTranscript = true,
                renderedTranscriptText = "new transcript",
                selectedTranscriptPassage = "hello",
                transcriptSelectionActionInFlight = TranscriptPassageAction.SUMMARIZE,
            )

        assertFalse(
            state.matchesTranscriptSelectionRequest(
                selection = "hello",
                transcriptText = "old transcript",
                action = TranscriptPassageAction.SUMMARIZE,
            ),
        )
    }

    @Test
    fun `validateTranscriptSelectionActionRequest prompts when ai is unavailable`() {
        val state =
            ProcessingStudioState(
                isSelectingTranscript = true,
                selectedTranscriptPassage = "hello",
            )

        assertEquals(
            "Add an API key in AI Processing settings to use transcript tools",
            state.validateTranscriptSelectionActionRequest(hasApiKey = false),
        )
    }
}
