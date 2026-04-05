package dev.chirpboard.app.data.entity

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
)
