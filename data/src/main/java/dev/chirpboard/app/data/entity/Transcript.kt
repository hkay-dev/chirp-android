package dev.chirpboard.app.data.entity

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
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
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("recordingId", unique = true)],
)
@Keep
data class Transcript(
    @PrimaryKey
    val id: UUID = UUID.randomUUID(),
    /** Associated recording */
    val recordingId: UUID,
    /** Raw recognizer transcript text aligned with any persisted timing rows */
    val rawText: String,
    /** LLM-processed text (if processing was applied) */
    val processedText: String? = null,
    /** Processing mode used (null = no LLM processing) */
    val processingMode: String? = null,
    /** User-authored correction text layered on top of pipeline-owned fields */
    val manualCorrectionText: String? = null,
    /** Effective transcript text that the saved manual correction was based on */
    val manualCorrectionSourceText: String? = null,
    /** LLM-generated summary for list display */
    val summary: String? = null,
    /** When the transcript was created */
    val createdAt: Date = Date(),
    /** When the transcript was last modified */
    val updatedAt: Date = Date(),
) {
    val pipelineText: String
        get() = processedText ?: rawText

    val effectiveText: String
        get() = manualCorrectionText ?: pipelineText

    val hasManualCorrection: Boolean
        get() = !manualCorrectionText.isNullOrBlank()
}
