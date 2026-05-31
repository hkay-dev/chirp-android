package dev.chirpboard.app.data.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.util.UUID

/**
 * Junction table for profile default tag relationships.
 */
@Entity(
    tableName = "profile_default_tags",
    primaryKeys = ["profileId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
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
data class ProfileDefaultTag(
    val profileId: UUID,
    val tagId: UUID,
)
