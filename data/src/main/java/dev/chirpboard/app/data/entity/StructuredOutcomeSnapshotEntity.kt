package dev.chirpboard.app.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import dev.chirpboard.app.data.model.StructuredOutcomeGenerationStatus
import dev.chirpboard.app.data.model.StructuredOutcomeSnapshot
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Date
import java.util.UUID

@Entity(
    tableName = "structured_outcome_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = Recording::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class StructuredOutcomeSnapshotEntity(
    @PrimaryKey
    val recordingId: UUID,
    val sourceTranscriptRevision: String? = null,
    val generationStatus: StructuredOutcomeGenerationStatus,
    val generatedAt: Date? = null,
    val lastAttemptedAt: Date,
    val failureMessage: String? = null,
    val taskItemsPayload: String? = null,
    val decisionItemsPayload: String? = null,
    val followUpItemsPayload: String? = null,
)

internal fun StructuredOutcomeSnapshotEntity.toModel(): StructuredOutcomeSnapshot =
    StructuredOutcomeSnapshot(
        recordingId = recordingId,
        sourceTranscriptRevision = sourceTranscriptRevision,
        generationStatus = generationStatus,
        generatedAt = generatedAt,
        lastAttemptedAt = lastAttemptedAt,
        failureMessage = failureMessage,
        tasks = decodeStructuredOutcomeItems(taskItemsPayload),
        decisions = decodeStructuredOutcomeItems(decisionItemsPayload),
        followUps = decodeStructuredOutcomeItems(followUpItemsPayload),
    )

internal fun StructuredOutcomeSnapshot.toEntity(): StructuredOutcomeSnapshotEntity =
    StructuredOutcomeSnapshotEntity(
        recordingId = recordingId,
        sourceTranscriptRevision = sourceTranscriptRevision,
        generationStatus = generationStatus,
        generatedAt = generatedAt,
        lastAttemptedAt = lastAttemptedAt,
        failureMessage = failureMessage,
        taskItemsPayload = encodeStructuredOutcomeItems(tasks),
        decisionItemsPayload = encodeStructuredOutcomeItems(decisions),
        followUpItemsPayload = encodeStructuredOutcomeItems(followUps),
    )

private fun encodeStructuredOutcomeItems(items: List<String>): String? {
    if (items.isEmpty()) return null
    val encoder = Base64.getEncoder()
    return items.joinToString(separator = "\n") { item ->
        encoder.encodeToString(item.toByteArray(StandardCharsets.UTF_8))
    }
}

private fun decodeStructuredOutcomeItems(payload: String?): List<String> {
    if (payload.isNullOrBlank()) return emptyList()
    val decoder = Base64.getDecoder()
    return payload
        .lineSequence()
        .filter { it.isNotBlank() }
        .map { encoded ->
            String(decoder.decode(encoded), StandardCharsets.UTF_8)
        }.toList()
}
