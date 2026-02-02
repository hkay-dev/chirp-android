package dev.parakeeboard.app.data.dao

import androidx.room.*
import dev.parakeeboard.app.data.entity.Profile
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface ProfileDao {
    
    @Query("SELECT * FROM profiles ORDER BY sortOrder ASC, name ASC")
    fun getAllProfiles(): Flow<List<Profile>>
    
    @Query("SELECT * FROM profiles ORDER BY sortOrder ASC, name ASC")
    suspend fun getAllProfilesList(): List<Profile>
    
    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getProfile(id: UUID): Profile?
    
    @Query("SELECT * FROM profiles WHERE id = :id")
    fun getProfileFlow(id: UUID): Flow<Profile?>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: Profile)
    
    @Update
    suspend fun update(profile: Profile)
    
    @Delete
    suspend fun delete(profile: Profile)
    
    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteById(id: UUID)
    
    @Query("SELECT MAX(sortOrder) FROM profiles")
    suspend fun getMaxSortOrder(): Int?
    
    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun getCount(): Int
}
