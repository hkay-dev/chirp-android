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
class ProfileRepository
    @Inject
    constructor(
        private val profileDao: ProfileDao,
    ) {
        companion object {
            private const val TAG = "ProfileRepository"
        }

        fun getAllProfiles(): Flow<RepositoryFlowState<List<Profile>>> =
            profileDao.getAllProfiles().catchRepositoryFlowState(TAG, emptyList())

        suspend fun getAllProfilesList(): List<Profile> = profileDao.getAllProfilesList()

        suspend fun getProfile(id: UUID): Profile? = profileDao.getProfile(id)

        suspend fun getProfiles(ids: List<UUID>): Map<UUID, Profile> =
            if (ids.isEmpty()) {
                emptyMap()
            } else {
                profileDao.getProfiles(ids).associateBy { it.id }
            }

        fun getProfileFlow(id: UUID): Flow<RepositoryFlowState<Profile?>> =
            profileDao.getProfileFlow(id).catchRepositoryFlowState(TAG, null)

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
            val quickStartPinned: Boolean = false,
        )

        suspend fun createProfile(request: CreateProfileRequest): Profile {
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
                    isQuickStartPinned = request.quickStartPinned,
                ).withDefaultTags(request.defaultTagIds)
            profileDao.insert(profile)
            return profile
        }

        suspend fun insert(profile: Profile) = profileDao.insert(profile)

        suspend fun update(profile: Profile) = profileDao.update(profile)

        suspend fun delete(profile: Profile) = profileDao.delete(profile)

        suspend fun deleteById(id: UUID) = profileDao.deleteById(id)

        suspend fun getCount(): Int = profileDao.getCount()
    }
