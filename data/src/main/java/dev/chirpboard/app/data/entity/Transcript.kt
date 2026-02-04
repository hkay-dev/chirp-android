package dev.chirpboard.app.data.entity

import androidx.room.*
import java.util.Date
import java.util.UUID

/**
 * Represents the transcript of a recording.
 */
@Entity(
    tableName = "transcripts",
    foreignKeys = [
        ForeignKey(
            entity = Recording::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("recordingId", unique = true)]
)
data class Transcript(
    @PrimaryKey
    val id: UUID = UUID.randomUUID(),
    
    /** Associated recording */
    val recordingId: UUID,
    
    /** Raw transcription text (after word replacements) */
    val rawText: String,
    
    /** LLM-processed text (if processing was applied) */
    val processedText: String? = null,
    
    /** Processing mode used (null = no LLM processing) */
    val processingMode: String? = null,
    
    /** LLM-generated summary for list display */
    val summary: String? = null,
    
    /** When the transcript was created */
    val createdAt: Date = Date(),
    
    /** When the transcript was last modified */
    val updatedAt: Date = Date()
)
