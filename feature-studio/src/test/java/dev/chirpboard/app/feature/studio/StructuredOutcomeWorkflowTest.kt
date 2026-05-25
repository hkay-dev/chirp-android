package dev.chirpboard.app.feature.studio

import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.model.StructuredOutcomeGenerationStatus
import dev.chirpboard.app.data.model.StructuredOutcomeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import java.util.UUID

class StructuredOutcomeWorkflowTest {
    @Test
    fun `validateStructuredOutcomeGenerationRequest requires completed transcript and api key`() {
        assertEquals(
            "Structured outcomes are available after processing finishes",
            validateStructuredOutcomeGenerationRequest(
                recordingStatus = RecordingStatus.ENHANCING,
                effectiveTranscriptText = "hello",
                hasApiKey = true,
                isGenerating = false,
            ),
        )

        assertEquals(
            "Structured outcomes need transcript text first",
            validateStructuredOutcomeGenerationRequest(
                recordingStatus = RecordingStatus.COMPLETED,
                effectiveTranscriptText = "",
                hasApiKey = true,
                isGenerating = false,
            ),
        )

        assertEquals(
            "Add an API key in AI Processing settings to generate structured outcomes",
            validateStructuredOutcomeGenerationRequest(
                recordingStatus = RecordingStatus.COMPLETED,
                effectiveTranscriptText = "hello",
                hasApiKey = false,
                isGenerating = false,
            ),
        )

        assertEquals(
            "Structured outcomes are already generating",
            validateStructuredOutcomeGenerationRequest(
                recordingStatus = RecordingStatus.COMPLETED,
                effectiveTranscriptText = "hello",
                hasApiKey = true,
                isGenerating = true,
            ),
        )

        assertEquals(
            null,
            validateStructuredOutcomeGenerationRequest(
                recordingStatus = RecordingStatus.COMPLETED,
                effectiveTranscriptText = "hello",
                hasApiKey = true,
                isGenerating = false,
            ),
        )
    }

    @Test
    fun `buildStructuredOutcomeSectionState marks stored snapshot stale after transcript changes`() {
        val snapshot =
            StructuredOutcomeSnapshot(
                recordingId = UUID.randomUUID(),
                sourceTranscriptRevision = "older-revision",
                generationStatus = StructuredOutcomeGenerationStatus.READY,
                generatedAt = Date(1_000L),
                lastAttemptedAt = Date(1_000L),
                tasks = listOf("Review the draft"),
                followUps = listOf("Email Sam"),
            )

        val sectionState =
            buildStructuredOutcomeSectionState(
                recordingStatus = RecordingStatus.COMPLETED,
                effectiveTranscriptText = "review the draft and email Sam",
                snapshot = snapshot,
                isGenerating = false,
            )

        assertTrue(sectionState.isVisible)
        assertTrue(sectionState.hasReadySnapshot)
        assertTrue(sectionState.isStale)
        assertEquals(listOf("Review the draft"), sectionState.tasks.map(StructuredOutcomeItemUi::text))
        assertEquals(listOf("Email Sam"), sectionState.followUps.map(StructuredOutcomeItemUi::text))
    }

    @Test
    fun `buildStructuredOutcomeAskAiDraft keeps the selected item context`() {
        val prompt =
            buildStructuredOutcomeAskAiDraft(
                StructuredOutcomeItemUi(
                    id = "task-0",
                    group = StructuredOutcomeGroup.TASKS,
                    text = "Call Alex before Friday",
                ),
            )

        assertTrue(prompt.contains("task", ignoreCase = true))
        assertTrue(prompt.contains("Call Alex before Friday"))
    }

    @Test
    fun `buildStructuredOutcomeSectionState keeps failure local when no ready snapshot exists`() {
        val sectionState =
            buildStructuredOutcomeSectionState(
                recordingStatus = RecordingStatus.COMPLETED,
                effectiveTranscriptText = "hello",
                snapshot =
                    StructuredOutcomeSnapshot(
                        recordingId = UUID.randomUUID(),
                        sourceTranscriptRevision = "rev",
                        generationStatus = StructuredOutcomeGenerationStatus.FAILED,
                        generatedAt = null,
                        lastAttemptedAt = Date(2_000L),
                        failureMessage = "Schema parse failed",
                    ),
                isGenerating = false,
            )

        assertFalse(sectionState.hasReadySnapshot)
        assertEquals("Schema parse failed", sectionState.failureMessage)
        assertTrue(sectionState.canRunGeneration)
    }
}
