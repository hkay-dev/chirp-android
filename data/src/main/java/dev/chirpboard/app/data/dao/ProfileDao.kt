package dev.chirpboard.app.data.dao

import androidx.room.*
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.ProfileDefaultTag
import dev.chirpboard.app.data.entity.Tag
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

    @Query("SELECT * FROM profiles WHERE id IN (:ids)")
    suspend fun getProfiles(ids: List<UUID>): List<Profile>

    @Query("SELECT id FROM tags WHERE id IN (:ids)")
    suspend fun getExistingTagIds(ids: List<UUID>): List<UUID>

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun getProfileFlow(id: UUID): Flow<Profile?>

    @Insert
    suspend fun insert(profile: Profile)

    @Update
    suspend fun update(profile: Profile): Int

    @Delete
    suspend fun delete(profile: Profile)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteById(id: UUID)

    @Query("SELECT MAX(sortOrder) FROM profiles")
    suspend fun getMaxSortOrder(): Int?

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun getCount(): Int

    @Query(
        """
        SELECT t.* FROM tags t
        INNER JOIN profile_default_tags pdt ON pdt.tagId = t.id
        WHERE pdt.profileId = :profileId
        ORDER BY t.name ASC, t.id ASC
        """,
    )
    fun getDefaultTagsForProfile(profileId: UUID): Flow<List<Tag>>

    @Query(
        """
        SELECT t.* FROM tags t
        INNER JOIN profile_default_tags pdt ON pdt.tagId = t.id
        WHERE pdt.profileId = :profileId
        ORDER BY t.name ASC, t.id ASC
        """,
    )
    suspend fun getDefaultTagsForProfileList(profileId: UUID): List<Tag>

    @Query(
        """
        SELECT pdt.tagId FROM profile_default_tags pdt
        INNER JOIN tags t ON t.id = pdt.tagId
        WHERE pdt.profileId = :profileId
        ORDER BY t.name ASC, t.id ASC
        """,
    )
    suspend fun getDefaultTagIds(profileId: UUID): List<UUID>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDefaultTags(defaultTags: List<ProfileDefaultTag>)

    @Query("DELETE FROM profile_default_tags WHERE profileId = :profileId")
    suspend fun deleteDefaultTagsForProfile(profileId: UUID)

    @Query("SELECT COUNT(*) FROM profile_default_tags WHERE profileId = :profileId")
    suspend fun getDefaultTagCount(profileId: UUID): Int

    @Transaction
    suspend fun insertWithDefaultTags(
        profile: Profile,
        tagIds: List<UUID>,
    ) {
        val uniqueTagIds = validatedDefaultTagIds(tagIds)
        insert(profile)
        if (uniqueTagIds.isNotEmpty()) {
            insertDefaultTags(uniqueTagIds.map { tagId -> ProfileDefaultTag(profile.id, tagId) })
        }
    }

    @Transaction
    suspend fun updateWithDefaultTags(
        profile: Profile,
        tagIds: List<UUID>,
    ): Boolean {
        val uniqueTagIds = validatedDefaultTagIds(tagIds)
        if (update(profile) == 0) {
            return false
        }
        deleteDefaultTagsForProfile(profile.id)
        if (uniqueTagIds.isNotEmpty()) {
            insertDefaultTags(uniqueTagIds.map { tagId -> ProfileDefaultTag(profile.id, tagId) })
        }
        return true
    }

    @Transaction
    suspend fun replaceDefaultTagsForProfile(
        profileId: UUID,
        tagIds: List<UUID>,
    ): Boolean {
        if (getProfile(profileId) == null) {
            return false
        }
        val uniqueTagIds = validatedDefaultTagIds(tagIds)
        deleteDefaultTagsForProfile(profileId)
        if (uniqueTagIds.isNotEmpty()) {
            insertDefaultTags(uniqueTagIds.map { tagId -> ProfileDefaultTag(profileId, tagId) })
        }
        return true
    }

    private suspend fun validatedDefaultTagIds(tagIds: List<UUID>): List<UUID> {
        val uniqueTagIds = tagIds.distinct()
        if (uniqueTagIds.isEmpty()) {
            return emptyList()
        }
        val existingTagIds = getExistingTagIds(uniqueTagIds).toSet()
        val missingTagIds = uniqueTagIds.filterNot(existingTagIds::contains)
        require(missingTagIds.isEmpty()) {
            "Default tag IDs must reference existing tags: ${missingTagIds.joinToString()}"
        }
        return uniqueTagIds
    }
}
