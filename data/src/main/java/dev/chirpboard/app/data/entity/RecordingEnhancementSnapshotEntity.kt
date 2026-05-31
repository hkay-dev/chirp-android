package dev.chirpboard.app.data.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import dev.chirpboard.app.data.model.EnhancementSubworkStatus
import java.util.Date
import java.util.UUID

@Entity(
    tableName = "recording_enhancement_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = Recording::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
@Keep
data class RecordingEnhancementSnapshotEntity(
    @PrimaryKey
    val recordingId: UUID,
    val schemaVersion: Int = 1,
    val sourceTranscriptRevision: String,
    val sourceProcessedTextRevision: String?,
    val processingModeRequested: Boolean,
    val processingModeId: String?,
    val processingModeLabel: String?,
    val processingModeType: String?,
    val processingModePrompt: String?,
    val processingModeStatus: EnhancementSubworkStatus,
    val processingModeErrorMessage: String?,
    val titleRequested: Boolean,
    val titleStatus: EnhancementSubworkStatus,
    val titleErrorMessage: String?,
    val summaryRequested: Boolean,
    val summaryStatus: EnhancementSubworkStatus,
    val summaryErrorMessage: String?,
    val llmProviderId: String?,
    val llmModelId: String?,
    val activeEnhancementExecutionToken: String?,
    val legacyRequiresResolution: Boolean,
    val createdAt: Date = Date(),
    val lastAttemptedAt: Date? = null,
    val lastErrorMessage: String? = null,
)
