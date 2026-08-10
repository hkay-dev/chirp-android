package dev.chirpboard.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.chirpboard.app.data.entity.RecordingEnhancementSnapshotEntity
import java.util.Date
import java.util.UUID

@Dao
interface RecordingEnhancementSnapshotDao {
    @Query("SELECT * FROM recording_enhancement_snapshots WHERE recordingId = :recordingId")
    suspend fun getSnapshot(recordingId: UUID): RecordingEnhancementSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: RecordingEnhancementSnapshotEntity)

    @Query("DELETE FROM recording_enhancement_snapshots WHERE recordingId = :recordingId")
    suspend fun deleteByRecordingId(recordingId: UUID)

    @Query(
        """
        UPDATE recording_enhancement_snapshots
        SET lastAttemptedAt = :lastAttemptedAt,
            lastErrorMessage = NULL
        WHERE recordingId = :recordingId
            AND activeEnhancementExecutionToken = :executionToken
        """,
    )
    suspend fun markAttempt(
        recordingId: UUID,
        executionToken: String,
        lastAttemptedAt: Date = Date(),
    ): Int
}
