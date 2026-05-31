package dev.chirpboard.app.data.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import java.util.Date
import java.util.UUID

@Entity(
    tableName = "recording_enhancement_intents",
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
data class RecordingEnhancementIntentEntity(
    @PrimaryKey
    val recordingId: UUID,
    val processingModeId: String?,
    val autoTitle: Boolean,
    val autoSummary: Boolean,
    val createdAt: Date = Date(),
    val lastAttemptedAt: Date? = null,
    val lastErrorMessage: String? = null,
)
