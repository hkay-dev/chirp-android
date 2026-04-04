package dev.chirpboard.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.chirpboard.app.data.entity.WordReplacement
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface WordReplacementDao {
    @Query("SELECT * FROM word_replacements ORDER BY original ASC")
    fun getAllReplacements(): Flow<List<WordReplacement>>

    @Query("SELECT * FROM word_replacements WHERE enabled = 1 ORDER BY original ASC")
    suspend fun getEnabledReplacements(): List<WordReplacement>

    @Query("SELECT * FROM word_replacements WHERE id = :id")
    suspend fun getReplacement(id: UUID): WordReplacement?

    @Query("SELECT * FROM word_replacements WHERE original = :original")
    suspend fun getReplacementByOriginal(original: String): WordReplacement?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(replacement: WordReplacement)

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

    @Query("SELECT COUNT(*) FROM word_replacements")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM word_replacements WHERE enabled = 1")
    suspend fun getEnabledCount(): Int
}
