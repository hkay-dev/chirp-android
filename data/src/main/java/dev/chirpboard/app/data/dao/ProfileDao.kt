package dev.chirpboard.app.data.dao

import androidx.room.*
import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.ProfileDefaultTag
import dev.chirpboard.app.data.entity.Tag
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** A recording→profile assignment keyed by the profile's NAME, for re-linking across a REPLACE restore. */
data class RecordingProfileNameLink(
    val recordingId: UUID,
    val profileName: String,
)

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

    @Query("SELECT * FROM profiles WHERE name = :name LIMIT 1")
    suspend fun getProfileByName(name: String): Profile?

    @Insert
    suspend fun insert(profile: Profile)

    @Update
    suspend fun update(profile: Profile): Int

    @Delete
    suspend fun delete(profile: Profile)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteById(id: UUID)

    @Query("DELETE FROM profiles")
    suspend fun deleteAllProfiles()

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

    @Query(
        """
        SELECT r.id AS recordingId, p.name AS profileName
        FROM recordings r
        INNER JOIN profiles p ON p.id = r.profileId
    """,
    )
    suspend fun getRecordingProfileLinksByName(): List<RecordingProfileNameLink>

    @Query("UPDATE recordings SET profileId = :profileId WHERE id IN (:recordingIds)")
    suspend fun assignRecordingsToProfile(
        profileId: UUID,
        recordingIds: List<UUID>,
    )

    /**
     * Backup restore, REPLACE semantics: clears every profile, then inserts the backup's
     * profiles. Deleting a profile fires the recordings FK (profileId, SET_NULL on delete)
     * for EVERY recording — including ones whose profile the backup re-creates — so the
     * recording→profile assignments are snapshotted by profile NAME (the same natural key
     * the MERGE path uses) before the delete and re-bound to the inserted profiles
     * afterwards, all inside the one transaction. Only recordings whose profile is NOT in
     * the backup end up unassigned, exactly what the import UI promises; nothing is ever
     * orphaned. profile_default_tags rows cascade away and are rebuilt from the backup's
     * own default-tag lists. Profiles are re-inserted in backup order, so sortOrder is
     * rewritten to the list position.
     */
    @Transaction
    suspend fun replaceAllProfiles(entries: List<ProfileBackupEntry>): BackupUpsertCounts {
        val recordingLinks = getRecordingProfileLinksByName()
        deleteAllProfiles()
        entries.forEachIndexed { index, entry ->
            insert(entry.profile.copy(sortOrder = index))
            insertExistingDefaultTags(entry.profile.id, entry.defaultTagIds)
        }
        val profileIdsByName = entries.associate { it.profile.name to it.profile.id }
        recordingLinks
            .groupBy({ profileIdsByName[it.profileName] }, { it.recordingId })
            .forEach { (profileId, recordingIds) ->
                if (profileId != null) {
                    // SQLite caps bind variables per statement; chunk large libraries.
                    recordingIds.chunked(REBIND_CHUNK_SIZE).forEach { chunk ->
                        assignRecordingsToProfile(profileId, chunk)
                    }
                }
            }
        return BackupUpsertCounts(inserted = entries.size, updated = 0)
    }

    /**
     * Backup restore, MERGE semantics: upserts by profile name (the natural key). A name
     * match updates the existing row in place — the existing id AND sortOrder are kept so
     * recordings stay linked and the user's current ordering is undisturbed. New profiles
     * are appended at the end and keep their backup id unless it collides.
     */
    @Transaction
    suspend fun upsertProfilesByName(entries: List<ProfileBackupEntry>): BackupUpsertCounts {
        var inserted = 0
        var updated = 0
        for (entry in entries) {
            val existing = getProfileByName(entry.profile.name)
            if (existing != null) {
                update(entry.profile.copy(id = existing.id, sortOrder = existing.sortOrder))
                deleteDefaultTagsForProfile(existing.id)
                insertExistingDefaultTags(existing.id, entry.defaultTagIds)
                updated++
            } else {
                val safeId = if (getProfile(entry.profile.id) == null) entry.profile.id else UUID.randomUUID()
                val sortOrder = (getMaxSortOrder() ?: 0) + 1
                insert(entry.profile.copy(id = safeId, sortOrder = sortOrder))
                insertExistingDefaultTags(safeId, entry.defaultTagIds)
                inserted++
            }
        }
        return BackupUpsertCounts(inserted = inserted, updated = updated)
    }

    /**
     * Unlike [validatedDefaultTagIds] (which throws for editor flows), backup restore silently
     * drops default-tag references whose tag no longer exists: a dangling reference inside an
     * old backup file must never abort the whole profiles section.
     */
    private suspend fun insertExistingDefaultTags(
        profileId: UUID,
        tagIds: List<UUID>,
    ) {
        val uniqueTagIds = tagIds.distinct()
        if (uniqueTagIds.isEmpty()) return
        val existingTagIds = getExistingTagIds(uniqueTagIds)
        if (existingTagIds.isNotEmpty()) {
            insertDefaultTags(existingTagIds.map { tagId -> ProfileDefaultTag(profileId, tagId) })
        }
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

    companion object {
        /** Stays under SQLite's 999 bind-variable limit (one slot is the profileId itself). */
        const val REBIND_CHUNK_SIZE = 900
    }
}
