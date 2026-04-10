package dev.chirpboard.app.data.repository

import dev.chirpboard.app.data.dao.ProfileDao
import dev.chirpboard.app.data.entity.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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
        fun getAllProfiles(): Flow<List<Profile>> =
            profileDao.getAllProfiles().catch {
                if (it is CancellationException) throw it
                emit(emptyList())
            }

        suspend fun getAllProfilesList(): List<Profile> = profileDao.getAllProfilesList()

        suspend fun getProfile(id: UUID): Profile? = profileDao.getProfile(id)

        suspend fun getProfiles(ids: List<UUID>): Map<UUID, Profile> =
            if (ids.isEmpty()) {
                emptyMap()
            } else {
                profileDao.getProfiles(ids).associateBy { it.id }
            }

        fun getProfileFlow(id: UUID): Flow<Profile?> =
            profileDao.getProfileFlow(id).catch {
                if (it is CancellationException) throw it
                emit(null)
            }

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
