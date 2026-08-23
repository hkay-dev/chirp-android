package dev.chirpboard.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import dev.chirpboard.app.data.entity.DICTATION_HISTORY_MAX_ENTRIES
import dev.chirpboard.app.data.entity.DictationHistoryEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface DictationHistoryDao {
    @Query("SELECT * FROM dictation_history ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<DictationHistoryEntry>>

    @Insert
    suspend fun insert(entry: DictationHistoryEntry): Long

    @Query(
        """
        DELETE FROM dictation_history WHERE id NOT IN (
            SELECT id FROM dictation_history ORDER BY createdAt DESC, id DESC LIMIT :keep
        )
        """,
    )
    suspend fun pruneToNewest(keep: Int)

    /**
     * HIST-1: the only write path — inserts and prunes atomically so the table can never
     * grow past the retention cap, even under concurrent dictations.
     */
    @Transaction
    suspend fun record(entry: DictationHistoryEntry) {
        insert(entry)
        pruneToNewest(DICTATION_HISTORY_MAX_ENTRIES)
    }

    @Query("DELETE FROM dictation_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM dictation_history")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM dictation_history")
    suspend fun getCount(): Int
}
