package dev.chirpboard.app.data.repository

import androidx.room.withTransaction
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
class ProfileRepository
    @Inject
    constructor(
        private val profileDao: ProfileDao,
        private val db: dev.chirpboard.app.data.db.AppDatabase,
    ) {
        /** Get all profiles ordered by sort order then name */
        fun getAllProfiles(): Flow<List<Profile>> = profileDao.getAllProfiles()

        /** Get all profiles as a list */
        suspend fun getAllProfilesList(): List<Profile> = profileDao.getAllProfilesList()

        /** Get a single profile by ID */
        suspend fun getProfile(id: UUID): Profile? = profileDao.getProfile(id)

        /** Get profile as Flow for reactive updates */
        fun getProfileFlow(id: UUID): Flow<Profile?> = profileDao.getProfileFlow(id)

        data class CreateProfileRequest(
            val name: String,
            val icon: String? = null,
            val defaultProcessingMode: String? = null,
            val autoTranscribe: Boolean = true,
            val autoTitle: Boolean = false,
            val autoSummary: Boolean = false,
            val obsidianVaultPath: String? = null,
            val autoExportToObsidian: Boolean = false,
            val defaultTagIds: List<UUID> = emptyList(),
        )

        /** Create a new profile */
        suspend fun createProfile(request: CreateProfileRequest): Profile {
            return db.withTransaction {
                val maxOrder = profileDao.getMaxSortOrder() ?: 0
                val profile =
                    Profile(
                        name = request.name,
                        icon = request.icon,
                        defaultProcessingMode = request.defaultProcessingMode,
                        autoTranscribe = request.autoTranscribe,
                        autoTitle = request.autoTitle,
                        autoSummary = request.autoSummary,
                        obsidianVaultPath = request.obsidianVaultPath,
                        autoExportToObsidian = request.autoExportToObsidian,
                        sortOrder = maxOrder + 1,
                    ).withDefaultTags(request.defaultTagIds)
                profileDao.insert(profile)
                profile
            }
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
