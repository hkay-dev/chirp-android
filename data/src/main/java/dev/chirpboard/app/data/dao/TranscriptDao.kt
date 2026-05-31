package dev.chirpboard.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.entity.TranscriptTiming
import dev.chirpboard.app.data.model.TranscriptPreview
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface TranscriptDao {
    @Query("SELECT * FROM transcripts WHERE recordingId IN (:recordingIds)")
    suspend fun getTranscripts(recordingIds: List<UUID>): List<Transcript>

    @Query(
        """
        SELECT
            recordingId,
            summary,
            substr(
                COALESCE(NULLIF(manualCorrectionText, ''), NULLIF(processedText, ''), rawText),
                1,
                :previewLimit
            ) AS previewText
        FROM transcripts
        WHERE recordingId IN (:recordingIds)
        """,
    )
    fun getTranscriptPreviewsFlow(
        recordingIds: List<UUID>,
        previewLimit: Int,
    ): Flow<List<TranscriptPreview>>

    @Query("SELECT * FROM transcripts WHERE recordingId = :recordingId")
    suspend fun getTranscript(recordingId: UUID): Transcript?

    @Query("SELECT * FROM transcripts WHERE recordingId = :recordingId")
    fun getTranscriptFlow(recordingId: UUID): Flow<Transcript?>

    @Query("SELECT * FROM transcripts WHERE id = :id")
    suspend fun getTranscriptById(id: UUID): Transcript?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transcript: Transcript)

    @Query("SELECT * FROM transcript_timings WHERE recordingId = :recordingId ORDER BY sequenceIndex ASC")
    suspend fun getTranscriptTimings(recordingId: UUID): List<TranscriptTiming>

    @Query("SELECT * FROM transcript_timings WHERE recordingId = :recordingId ORDER BY sequenceIndex ASC")
    fun getTranscriptTimingsFlow(recordingId: UUID): Flow<List<TranscriptTiming>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimings(timings: List<TranscriptTiming>)

    @Query("DELETE FROM transcript_timings WHERE recordingId = :recordingId")
    suspend fun deleteTimingsByRecordingId(recordingId: UUID)

    @Update
    suspend fun update(transcript: Transcript)

    @Query("UPDATE transcripts SET rawText = :rawText, updatedAt = :updatedAt WHERE recordingId = :recordingId")
    suspend fun updateRawText(
        recordingId: UUID,
        rawText: String,
        updatedAt: java.util.Date = java.util.Date(),
    )

    @Query(
        "UPDATE transcripts SET processedText = :processedText, processingMode = :mode, updatedAt = :updatedAt WHERE recordingId = :recordingId",
    )
    suspend fun updateProcessedText(
        recordingId: UUID,
        processedText: String,
        mode: String,
        updatedAt: java.util.Date = java.util.Date(),
    )

    @Query(
        "UPDATE transcripts SET manualCorrectionText = :manualCorrectionText, manualCorrectionSourceText = :manualCorrectionSourceText, updatedAt = :updatedAt WHERE recordingId = :recordingId",
    )
    suspend fun updateManualCorrection(
        recordingId: UUID,
        manualCorrectionText: String?,
        manualCorrectionSourceText: String?,
        updatedAt: java.util.Date = java.util.Date(),
    )

    @Query("UPDATE transcripts SET summary = :summary, updatedAt = :updatedAt WHERE recordingId = :recordingId")
    suspend fun updateSummary(
        recordingId: UUID,
        summary: String,
        updatedAt: java.util.Date = java.util.Date(),
    )

    @Delete
    suspend fun delete(transcript: Transcript)

    @Query("DELETE FROM transcripts WHERE recordingId = :recordingId")
    suspend fun deleteByRecordingId(recordingId: UUID)
}
