package dev.chirpboard.app.data.model

import java.util.Date
import java.util.UUID

enum class StructuredOutcomeGenerationStatus {
    READY,
    FAILED,
}

data class StructuredOutcomeSnapshot(
    val recordingId: UUID,
    val sourceTranscriptRevision: String? = null,
    val generationStatus: StructuredOutcomeGenerationStatus,
    val generatedAt: Date? = null,
    val lastAttemptedAt: Date,
    val failureMessage: String? = null,
    val tasks: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val followUps: List<String> = emptyList(),
) {
    val hasReadyPayload: Boolean
        get() = generatedAt != null
}
