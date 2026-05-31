package dev.chirpboard.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingStatus
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC, id ASC LIMIT 500")
    fun getAllRecordings(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecording(id: UUID): Recording?

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun getRecordingFlow(id: UUID): Flow<Recording?>

    @Query("SELECT * FROM recordings WHERE status = :status ORDER BY createdAt ASC, id ASC")
    fun getRecordingsByStatus(status: RecordingStatus): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE status IN (:statuses) ORDER BY createdAt ASC, id ASC")
    suspend fun getRecordingsByStatuses(statuses: List<RecordingStatus>): List<Recording>

    @Query("SELECT * FROM recordings WHERE profileId = :profileId ORDER BY createdAt DESC, id ASC")
    fun getRecordingsByProfile(profileId: UUID): Flow<List<Recording>>
    @Query("SELECT audioPath FROM recordings")
    suspend fun getAllAudioPaths(): List<String>

    @Query(
        """
        SELECT * FROM recordings 
        WHERE title LIKE '%' || :query || '%' 
        AND status != 'RECORDING'
        ORDER BY createdAt DESC, id ASC
        LIMIT :limit
    """,
    )
    fun searchRecordings(
        query: String,
        limit: Int,
    ): Flow<List<Recording>>

    @Insert
    suspend fun insert(recording: Recording)

    @Update
    suspend fun update(recording: Recording): Int

    @Query("UPDATE recordings SET status = :status WHERE id = :id")
    suspend fun updateStatusUnchecked(
        id: UUID,
        status: RecordingStatus,
    ): Int

    @Query("UPDATE recordings SET status = :status, errorMessage = :errorMessage WHERE id = :id")
    suspend fun updateStatusWithErrorUnchecked(
        id: UUID,
        status: RecordingStatus,
        errorMessage: String?,
    ): Int

    @Query("SELECT status FROM recordings WHERE id = :id")
    suspend fun getStatus(id: UUID): RecordingStatus?

    @Query(
        """
        UPDATE recordings
        SET status = :status, errorMessage = NULL
        WHERE id = :id AND status IN (:allowedStatuses)
        """,
    )
    suspend fun updateStatusIfCurrentIn(
        id: UUID,
        status: RecordingStatus,
        allowedStatuses: List<RecordingStatus>,
    ): Int

    @Query(
        """
        UPDATE recordings
        SET status = :status, errorMessage = :errorMessage
        WHERE id = :id AND status IN (:allowedStatuses)
        """,
    )
    suspend fun updateStatusWithErrorIfCurrentIn(
        id: UUID,
        status: RecordingStatus,
        errorMessage: String?,
        allowedStatuses: List<RecordingStatus>,
    ): Int

    @Query(
        """
        UPDATE recordings
        SET title = CASE WHEN :title IS NULL THEN title ELSE :title END,
            durationMs = :durationMs,
            status = :destinationStatus,
            errorMessage = NULL
        WHERE id = :id AND status = :expectedStatus
        """,
    )
    suspend fun finalizeInProgressIfCurrent(
        id: UUID,
        durationMs: Long,
        title: String?,
        expectedStatus: RecordingStatus = RecordingStatus.RECORDING,
        destinationStatus: RecordingStatus = RecordingStatus.PENDING_TRANSCRIPTION,
    ): Int

    @Query(
        """
        UPDATE recordings
        SET status = :status,
            errorMessage = :errorMessage,
            transcriptionExecutionToken = :executionToken
        WHERE id = :id
        """,
    )
    suspend fun updateStatusWithTranscriptionToken(
        id: UUID,
        status: RecordingStatus,
        errorMessage: String?,
        executionToken: String?,
    )

    @Query(
        """
        UPDATE recordings
        SET status = :newStatus,
            errorMessage = :errorMessage
        WHERE id = :id
            AND status = :expectedStatus
            AND transcriptionExecutionToken = :executionToken
        """,
    )
    suspend fun updateStatusForTranscriptionExecution(
        id: UUID,
        expectedStatus: RecordingStatus,
        executionToken: String,
        newStatus: RecordingStatus,
        errorMessage: String?,
    ): Int

    @Query(
        """
        UPDATE recordings
        SET status = :newStatus,
            errorMessage = :errorMessage
        WHERE id = :id
            AND status = :expectedStatus
        """,
    )
    suspend fun updateStatusIfCurrent(
        id: UUID,
        expectedStatus: RecordingStatus,
        newStatus: RecordingStatus,
        errorMessage: String?,
    ): Int

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

    @Query("DELETE FROM recordings WHERE id = :id AND status = :status")
    suspend fun deleteByIdAndStatus(
        id: UUID,
        status: RecordingStatus,
    ): Int

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: UUID)

    @Query("SELECT COUNT(*) FROM recordings")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM recordings WHERE status = :status")
    suspend fun getCountByStatus(status: RecordingStatus): Int

    @Query("DELETE FROM recordings")
    suspend fun deleteAll()
}
