package dev.chirpboard.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.chirpboard.app.data.entity.WordReplacement
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface WordReplacementDao {
    @Query("SELECT * FROM word_replacements ORDER BY original ASC")
    fun getAllReplacements(): Flow<List<WordReplacement>>

    @Query("SELECT * FROM word_replacements ORDER BY original ASC")
    suspend fun getAllReplacementsList(): List<WordReplacement>

    @Query("SELECT * FROM word_replacements WHERE enabled = 1 ORDER BY original ASC")
    suspend fun getEnabledReplacements(): List<WordReplacement>

    @Query("SELECT * FROM word_replacements WHERE id = :id")
    suspend fun getReplacement(id: UUID): WordReplacement?

    @Query("SELECT * FROM word_replacements WHERE original = :original")
    suspend fun getReplacementByOriginal(original: String): WordReplacement?

    @Query(
        "SELECT * FROM word_replacements WHERE original = :original AND replacement = :replacement AND caseSensitive = :caseSensitive LIMIT 1",
    )
    suspend fun getEquivalentReplacement(
        original: String,
        replacement: String,
        caseSensitive: Boolean,
    ): WordReplacement?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(replacement: WordReplacement)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReplacements(replacements: List<WordReplacement>)

    @Update
    suspend fun update(replacement: WordReplacement)

    @Query("UPDATE word_replacements SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(
        id: UUID,
        enabled: Boolean,
    )

    @Delete
    suspend fun delete(replacement: WordReplacement)

    @Query("DELETE FROM word_replacements WHERE id = :id")
    suspend fun deleteById(id: UUID)

    @Query("DELETE FROM word_replacements")
    suspend fun deleteAllReplacements()

    /** Backup restore, REPLACE semantics: clears every rule, then inserts the backup's rules. */
    @Transaction
    suspend fun replaceAllReplacements(replacements: List<WordReplacement>): BackupUpsertCounts {
        deleteAllReplacements()
        insertReplacements(replacements)
        return BackupUpsertCounts(inserted = replacements.size, updated = 0)
    }

    /**
     * Backup restore, MERGE semantics: upserts by the original phrase (the natural key). A
     * match updates the existing rule in place (keeping its id); new rules keep their backup
     * id unless it collides with an existing rule's id.
     */
    @Transaction
    suspend fun upsertReplacementsByOriginal(replacements: List<WordReplacement>): BackupUpsertCounts {
        var inserted = 0
        var updated = 0
        for (replacement in replacements) {
            val existing = getReplacementByOriginal(replacement.original)
            if (existing != null) {
                update(replacement.copy(id = existing.id))
                updated++
            } else {
                val safeId = if (getReplacement(replacement.id) == null) replacement.id else UUID.randomUUID()
                insert(replacement.copy(id = safeId))
                inserted++
            }
        }
        return BackupUpsertCounts(inserted = inserted, updated = updated)
    }

    @Query("SELECT COUNT(*) FROM word_replacements")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM word_replacements WHERE enabled = 1")
    suspend fun getEnabledCount(): Int
}
