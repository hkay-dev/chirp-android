package dev.chirpboard.app.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptionDao {
    @Query("SELECT * FROM transcriptions ORDER BY timestamp DESC")
    fun getAll(): Flow<List<Transcription>>

    @Insert
    suspend fun insert(transcription: Transcription): Long

    @Update
    suspend fun update(transcription: Transcription)

    @Delete
    suspend fun delete(transcription: Transcription)

    @Query("DELETE FROM transcriptions WHERE id = :id")
    suspend fun deleteById(id: Long)
}
