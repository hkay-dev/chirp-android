package dev.chirpboard.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingStatus
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC LIMIT 500")
    fun getAllRecordings(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecording(id: UUID): Recording?

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun getRecordingFlow(id: UUID): Flow<Recording?>

    @Query("SELECT * FROM recordings WHERE status = :status ORDER BY createdAt ASC")
    fun getRecordingsByStatus(status: RecordingStatus): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE status IN (:statuses) ORDER BY createdAt ASC")
    suspend fun getRecordingsByStatuses(statuses: List<RecordingStatus>): List<Recording>

    @Query("SELECT * FROM recordings WHERE profileId = :profileId ORDER BY createdAt DESC")
    fun getRecordingsByProfile(profileId: UUID): Flow<List<Recording>>

    @Query(
        """
        SELECT * FROM recordings 
        WHERE title LIKE '%' || :query || '%' 
        ORDER BY createdAt DESC
    """,
    )
    fun searchRecordings(query: String): Flow<List<Recording>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: Recording)

    @Update
    suspend fun update(recording: Recording)

    @Query("UPDATE recordings SET status = :status WHERE id = :id")
    suspend fun updateStatus(
        id: UUID,
        status: RecordingStatus,
    )

    @Query("UPDATE recordings SET status = :status, errorMessage = :errorMessage WHERE id = :id")
    suspend fun updateStatusWithError(
        id: UUID,
        status: RecordingStatus,
        errorMessage: String?,
    )

    @Query("UPDATE recordings SET title = :title WHERE id = :id")
    suspend fun updateTitle(
        id: UUID,
        title: String,
    )

    @Query("UPDATE recordings SET durationMs = :durationMs WHERE id = :id")
    suspend fun updateDuration(
        id: UUID,
        durationMs: Long,
    )

    @Query("UPDATE recordings SET lastExportedPath = :path, lastExportedAt = :exportedAt WHERE id = :id")
    suspend fun updateExportInfo(
        id: UUID,
        path: String,
        exportedAt: java.util.Date,
    )

    @Delete
    suspend fun delete(recording: Recording)

    @Query("DELETE FROM recordings WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<UUID>)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: UUID)

    @Query("SELECT COUNT(*) FROM recordings")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM recordings WHERE status = :status")
    suspend fun getCountByStatus(status: RecordingStatus): Int

    @Query("DELETE FROM recordings")
    suspend fun deleteAll()
}
