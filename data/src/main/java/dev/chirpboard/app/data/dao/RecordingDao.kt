package dev.chirpboard.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingLibraryStats
import dev.chirpboard.app.data.model.RecordingStatus
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC, id ASC LIMIT " + RecordingDao.HOME_RECORDINGS_LIMIT)
    fun getAllRecordings(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecording(id: UUID): Recording?

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun getRecordingFlow(id: UUID): Flow<Recording?>

    @Query("SELECT * FROM recordings WHERE status = :status ORDER BY createdAt ASC, id ASC")
    fun getRecordingsByStatus(status: RecordingStatus): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE status IN (:statuses) ORDER BY createdAt ASC, id ASC")
    suspend fun getRecordingsByStatuses(statuses: List<RecordingStatus>): List<Recording>

    @Query(
        """
        SELECT * FROM recordings
        WHERE terminalNotificationPending = 1 AND status IN ('COMPLETED', 'FAILED')
        ORDER BY createdAt ASC, id ASC
        """,
    )
    suspend fun getPendingTerminalNotifications(): List<Recording>

    @Query("SELECT * FROM recordings WHERE profileId = :profileId ORDER BY createdAt DESC, id ASC")
    fun getRecordingsByProfile(profileId: UUID): Flow<List<Recording>>
    @Query("SELECT audioPath FROM recordings")
    suspend fun getAllAudioPaths(): List<String>

    /**
     * Matches titles AND transcript text: the home list is capped at [HOME_RECORDINGS_LIMIT],
     * so search is the only path to older recordings — and their titles are often
     * auto-generated, so content search is what actually finds them. [pattern] is a
     * ready-made `%…%` LIKE pattern with `\`-escaped metacharacters (see the repository),
     * so a user typing `%` or `_` searches for those literal characters.
     */
    @Query(
        """
        SELECT r.* FROM recordings r
        LEFT JOIN transcripts t ON t.recordingId = r.id
        WHERE (
            r.title LIKE :pattern ESCAPE '\'
            OR t.rawText LIKE :pattern ESCAPE '\'
            OR t.processedText LIKE :pattern ESCAPE '\'
            OR t.manualCorrectionText LIKE :pattern ESCAPE '\'
        )
        AND r.status != 'RECORDING'
        ORDER BY r.createdAt DESC, r.id ASC
        LIMIT :limit
    """,
    )
    fun searchRecordings(
        pattern: String,
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
            audioPath = CASE WHEN :audioPath IS NULL THEN audioPath ELSE :audioPath END,
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
        audioPath: String? = null,
        expectedStatus: RecordingStatus = RecordingStatus.RECORDING,
        destinationStatus: RecordingStatus = RecordingStatus.PENDING_TRANSCRIPTION,
    ): Int

    /**
     * Token-handoff status update. The WHERE clause requires the row to still be in one of
     * [allowedCurrentStatuses], and, when [expectedExecutionToken] is non-null, to still carry
     * that execution token. This way a stale worker or queue pass holding an old token can
     * never regress a row that has already moved on (e.g. back out of COMPLETED).
     */
    @Query(
        """
        UPDATE recordings
        SET status = :status,
            errorMessage = :errorMessage,
            transcriptionExecutionToken = :executionToken
        WHERE id = :id
            AND status IN (:allowedCurrentStatuses)
            AND (:expectedExecutionToken IS NULL OR transcriptionExecutionToken = :expectedExecutionToken)
        """,
    )
    suspend fun updateStatusWithTranscriptionToken(
        id: UUID,
        status: RecordingStatus,
        errorMessage: String?,
        executionToken: String?,
        allowedCurrentStatuses: List<RecordingStatus>,
        expectedExecutionToken: String?,
    ): Int

    /**
     * Claims transcription ownership and re-arms the terminal notification outbox from the
     * immutable capture-time preference in the same database write.
     */
    @Query(
        """
        UPDATE recordings
        SET status = :status,
            errorMessage = :errorMessage,
            transcriptionExecutionToken = :executionToken,
            terminalNotificationPending = notifyWhenReady
        WHERE id = :id AND status IN (:allowedCurrentStatuses)
        """,
    )
    suspend fun claimTranscriptionExecution(
        id: UUID,
        status: RecordingStatus,
        errorMessage: String?,
        executionToken: String,
        allowedCurrentStatuses: List<RecordingStatus>,
    ): Int

    @Query(
        """
        UPDATE recordings
        SET terminalNotificationPending = notifyWhenReady
        WHERE id = :id
        """,
    )
    suspend fun rearmTerminalNotification(id: UUID): Int

    @Query(
        """
        UPDATE recordings
        SET terminalNotificationPending = 0
        WHERE id = :id AND terminalNotificationPending = 1 AND status = :expectedStatus
        """,
    )
    suspend fun clearPendingTerminalNotification(
        id: UUID,
        expectedStatus: RecordingStatus,
    ): Int

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

    /** Applies a generated title only while the title is untouched: a user rename always wins. */
    @Query("UPDATE recordings SET title = :title WHERE id = :id AND title = :expectedTitle")
    suspend fun updateTitleIfCurrent(
        id: UUID,
        title: String,
        expectedTitle: String,
    ): Int

    @Query("UPDATE recordings SET durationMs = :durationMs WHERE id = :id")
    suspend fun updateDuration(
        id: UUID,
        durationMs: Long,
    )

    @Query("SELECT notes FROM recordings WHERE id = :id")
    suspend fun getNotes(id: UUID): String?

    /**
     * Notes-only column write: never touches status, tokens, or any other finalize-owned
     * column, so it is always safe to run alongside the stop/finalize pipeline.
     */
    @Query("UPDATE recordings SET notes = :notes WHERE id = :id")
    suspend fun updateNotes(
        id: UUID,
        notes: String?,
    ): Int

    /**
     * Stamps the transcription engine exactly once. The null guard keeps retries and queue
     * reconciliation pinned to the same backend even if the global setting changes later.
     */
    @Query(
        """
        UPDATE recordings
        SET transcriptionEngineId = :engineId
        WHERE id = :id AND transcriptionEngineId IS NULL
        """,
    )
    suspend fun setTranscriptionEngineIfUnset(
        id: UUID,
        engineId: String,
    ): Int

    /**
     * Swaps a transcription-owned recording to a replacement audio file. The old path and
     * execution-token guards stop a stale worker from redirecting a row after another run has
     * taken ownership or already replaced the source.
     */
    @Query(
        """
        UPDATE recordings
        SET audioPath = :newAudioPath
        WHERE id = :id
            AND status = 'TRANSCRIBING'
            AND transcriptionExecutionToken = :executionToken
            AND audioPath = :expectedAudioPath
        """,
    )
    suspend fun swapAudioPathForTranscriptionExecution(
        id: UUID,
        executionToken: String,
        expectedAudioPath: String,
        newAudioPath: String,
    ): Int

    @Query(
        """
        UPDATE recordings
        SET transcriptionEngineId = :newEngineId
        WHERE id = :id
            AND status = 'TRANSCRIBING'
            AND transcriptionExecutionToken = :executionToken
            AND transcriptionEngineId = :expectedEngineId
        """,
    )
    suspend fun rerouteTranscriptionEngineForExecution(
        id: UUID,
        executionToken: String,
        expectedEngineId: String,
        newEngineId: String,
    ): Int

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

    /**
     * Full-table aggregate for the home header stats (DAT-006). Unlike [getAllRecordings]
     * (capped at [HOME_RECORDINGS_LIMIT] rows), this counts and sums EVERY recording so the
     * header never undercounts a 500+ library.
     */
    @Query(
        """
        SELECT COUNT(*) AS totalCount,
            COALESCE(SUM(durationMs), 0) AS totalDurationMs,
            COALESCE(SUM(CASE WHEN status = :completedStatus THEN 1 ELSE 0 END), 0) AS completedCount
        FROM recordings
        """,
    )
    fun getLibraryStats(completedStatus: RecordingStatus): Flow<RecordingLibraryStats>

    @Query("DELETE FROM recordings")
    suspend fun deleteAll()

    companion object {
        /**
         * Row cap for the home list query (DAT-006). The home screen shows a "showing latest"
         * footer once the full count exceeds this; older recordings stay reachable via search.
         */
        const val HOME_RECORDINGS_LIMIT = 500
    }
}
