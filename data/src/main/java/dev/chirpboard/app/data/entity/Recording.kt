package dev.chirpboard.app.data.entity

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import java.util.Date
import java.util.UUID

/**
 * Represents an audio recording.
 */
@Entity(
    tableName = "recordings",
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("profileId"),
        Index("createdAt"),
        Index("status"),
        Index("status", "createdAt"),
        Index("profileId", "createdAt"),
    ],
)
@Keep
data class Recording(
    @PrimaryKey
    val id: UUID = UUID.randomUUID(),
    /** User-visible title (auto-generated or manual) */
    val title: String,
    /** Path to the audio file (M4A) */
    val audioPath: String,
    /** Processing status */
    val status: RecordingStatus = RecordingStatus.PENDING_TRANSCRIPTION,
    /** Where the recording was created */
    val source: RecordingSource,
    /** Associated profile (nullable) */
    val profileId: UUID? = null,
    /** When the recording was created */
    val createdAt: Date = Date(),
    /** Duration in milliseconds */
    val durationMs: Long = 0,
    /** Error message if status is FAILED */
    val errorMessage: String? = null,
    /** Path where this was last exported to Obsidian */
    val lastExportedPath: String? = null,
    /** When this was last exported */
    val lastExportedAt: Date? = null,
    /** Active queued/running transcription execution token */
    val transcriptionExecutionToken: String? = null,
    /**
     * Freeform user note describing the recording (captured live on the record screen or
     * edited in the studio). Null when the user never wrote one. Written only through
     * [dev.chirpboard.app.data.repository.RecordingRepository.updateNotes].
     */
    val notes: String? = null,
    /**
     * File-level transcription engine captured before queued work starts. Null means a new row
     * whose engine has not been stamped yet; migrated rows are pinned to the local engine. The
     * worker resolves the current default once and persists it before touching the audio.
     */
    @ColumnInfo(defaultValue = "NULL")
    val transcriptionEngineId: String? = null,
    /** Optional processing mode captured by a durable dictation handoff. */
    @ColumnInfo(defaultValue = "NULL")
    val requestedProcessingModeId: String? = null,
    /** Optional LLM provider captured with [requestedProcessingModeId]. */
    @ColumnInfo(defaultValue = "NULL")
    val requestedLlmProviderId: String? = null,
    /** Optional LLM model captured with [requestedProcessingModeId]. */
    @ColumnInfo(defaultValue = "NULL")
    val requestedLlmModelId: String? = null,
    /** Immutable capture-time preference for a terminal ready or failure notification. */
    @ColumnInfo(defaultValue = "0")
    val notifyWhenReady: Boolean = false,
    /** Room-backed outbox marker, cleared only after the requested notification post succeeds. */
    @ColumnInfo(defaultValue = "0")
    val terminalNotificationPending: Boolean = false,
    /** Whether the requested enhancement fields form a complete capture-time snapshot. */
    @ColumnInfo(defaultValue = "0")
    val enhancementRequestSnapshotted: Boolean = false,
)
