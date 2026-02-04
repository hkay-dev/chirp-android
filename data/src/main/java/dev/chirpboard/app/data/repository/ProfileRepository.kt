package dev.chirpboard.app.data.repository

import dev.chirpboard.app.data.dao.ProfileDao
import dev.chirpboard.app.data.entity.Profile
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing recording profiles.
 */
@Singleton
class ProfileRepository @Inject constructor(
    private val profileDao: ProfileDao
) {
    
    /** Get all profiles ordered by sort order then name */
    fun getAllProfiles(): Flow<List<Profile>> = profileDao.getAllProfiles()
    
    /** Get all profiles as a list */
    suspend fun getAllProfilesList(): List<Profile> = profileDao.getAllProfilesList()
    
    /** Get a single profile by ID */
    suspend fun getProfile(id: UUID): Profile? = profileDao.getProfile(id)
    
    /** Get profile as Flow for reactive updates */
    fun getProfileFlow(id: UUID): Flow<Profile?> = profileDao.getProfileFlow(id)
    
    /** Create a new profile */
    suspend fun createProfile(
        name: String,
        icon: String? = null,
        defaultProcessingMode: String? = null,
        autoTranscribe: Boolean = true,
        autoTitle: Boolean = false,
        autoSummary: Boolean = false,
        obsidianVaultPath: String? = null,
        autoExportToObsidian: Boolean = false,
        defaultTagIds: List<UUID> = emptyList()
    ): Profile {
        val maxOrder = profileDao.getMaxSortOrder() ?: 0
        val profile = Profile(
            name = name,
            icon = icon,
            defaultProcessingMode = defaultProcessingMode,
            autoTranscribe = autoTranscribe,
            autoTitle = autoTitle,
            autoSummary = autoSummary,
            obsidianVaultPath = obsidianVaultPath,
            autoExportToObsidian = autoExportToObsidian,
            sortOrder = maxOrder + 1
        ).withDefaultTags(defaultTagIds)
        profileDao.insert(profile)
        return profile
    }
    
    /** Insert an existing profile */
    suspend fun insert(profile: Profile) = profileDao.insert(profile)
    
    /** Update a profile */
    suspend fun update(profile: Profile) = profileDao.update(profile)
    
    /** Delete a profile */
    suspend fun delete(profile: Profile) = profileDao.delete(profile)
    
    /** Delete a profile by ID */
    suspend fun deleteById(id: UUID) = profileDao.deleteById(id)
    
    /** Get profile count */
    suspend fun getCount(): Int = profileDao.getCount()
}
