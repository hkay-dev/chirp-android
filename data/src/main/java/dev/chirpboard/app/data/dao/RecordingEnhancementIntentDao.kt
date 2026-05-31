package dev.chirpboard.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.chirpboard.app.data.entity.RecordingEnhancementIntentEntity
import java.util.Date
import java.util.UUID

@Dao
interface RecordingEnhancementIntentDao {
    @Query("SELECT * FROM recording_enhancement_intents WHERE recordingId = :recordingId")
    suspend fun getIntent(recordingId: UUID): RecordingEnhancementIntentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(intent: RecordingEnhancementIntentEntity)

    @Query("DELETE FROM recording_enhancement_intents WHERE recordingId = :recordingId")
    suspend fun deleteByRecordingId(recordingId: UUID)

    @Query(
        "UPDATE recording_enhancement_intents SET lastAttemptedAt = :lastAttemptedAt, lastErrorMessage = NULL WHERE recordingId = :recordingId",
    )
    suspend fun markAttempt(
        recordingId: UUID,
        lastAttemptedAt: Date = Date(),
    )

    @Query(
        "UPDATE recording_enhancement_intents SET lastErrorMessage = :errorMessage WHERE recordingId = :recordingId",
    )
    suspend fun updateError(
        recordingId: UUID,
        errorMessage: String?,
    )
}
