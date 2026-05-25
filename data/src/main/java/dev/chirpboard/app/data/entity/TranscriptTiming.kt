package dev.chirpboard.app.data.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "transcript_timings",
    primaryKeys = ["recordingId", "sequenceIndex"],
    foreignKeys = [
        ForeignKey(
            entity = Recording::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recordingId")],
)
@Keep
data class TranscriptTiming(
    val recordingId: java.util.UUID,
    val sequenceIndex: Int,
    val text: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
)
