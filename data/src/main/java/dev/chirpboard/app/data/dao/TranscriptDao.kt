package dev.chirpboard.app.data.dao

import androidx.room.*
import dev.chirpboard.app.data.entity.Transcript
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface TranscriptDao {
    
    @Query("SELECT * FROM transcripts WHERE recordingId = :recordingId")
    suspend fun getTranscript(recordingId: UUID): Transcript?
    
    @Query("SELECT * FROM transcripts WHERE recordingId = :recordingId")
    fun getTranscriptFlow(recordingId: UUID): Flow<Transcript?>
    
    @Query("SELECT * FROM transcripts WHERE id = :id")
    suspend fun getTranscriptById(id: UUID): Transcript?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transcript: Transcript)
    
    @Update
    suspend fun update(transcript: Transcript)
    
    @Query("UPDATE transcripts SET rawText = :rawText, updatedAt = :updatedAt WHERE recordingId = :recordingId")
    suspend fun updateRawText(recordingId: UUID, rawText: String, updatedAt: java.util.Date = java.util.Date())
    
    @Query("UPDATE transcripts SET processedText = :processedText, processingMode = :mode, updatedAt = :updatedAt WHERE recordingId = :recordingId")
    suspend fun updateProcessedText(recordingId: UUID, processedText: String, mode: String, updatedAt: java.util.Date = java.util.Date())
    
    @Query("UPDATE transcripts SET summary = :summary, updatedAt = :updatedAt WHERE recordingId = :recordingId")
    suspend fun updateSummary(recordingId: UUID, summary: String, updatedAt: java.util.Date = java.util.Date())
    
    @Delete
    suspend fun delete(transcript: Transcript)
    
    @Query("DELETE FROM transcripts WHERE recordingId = :recordingId")
    suspend fun deleteByRecordingId(recordingId: UUID)
}
