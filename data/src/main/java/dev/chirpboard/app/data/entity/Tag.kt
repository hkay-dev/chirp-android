package dev.chirpboard.app.data.entity

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Represents a tag for organizing recordings.
 */
@Entity(
    tableName = "tags",
    indices = [Index("name")]
)
@Keep
data class Tag(
    @PrimaryKey
    val id: UUID = UUID.randomUUID(),
    /** Tag display name */
    val name: String,
    /** Color as hex string (e.g., "#FF5733") */
    val color: String? = null,
)

/**
 * Junction table for recording-tag relationship.
 */
@Entity(
    tableName = "recording_tags",
    primaryKeys = ["recordingId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = Recording::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tagId")],
)
@Keep
data class RecordingTag(
    val recordingId: UUID,
    val tagId: UUID,
)
