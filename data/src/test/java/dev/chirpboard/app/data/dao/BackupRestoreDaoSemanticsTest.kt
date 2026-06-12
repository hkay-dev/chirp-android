package dev.chirpboard.app.data.dao

import dev.chirpboard.app.data.entity.Profile
import dev.chirpboard.app.data.entity.ProfileDefaultTag
import dev.chirpboard.app.data.entity.RecordingTag
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.entity.WordReplacement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Merge/replace semantics for the backup-restore DAO operations.
 *
 * The logic under test lives in the DAOs' @Transaction default methods, which run as plain
 * Kotlin on the JVM when the abstract query methods are stubbed with in-memory fakes — so
 * these tests exercise the REAL production merge/replace code, not a re-implementation.
 * (Room only contributes the transaction wrapper, which has no behavior to assert here;
 * FK side effects like recordings.profileId SET_NULL are schema-level and device-tested.)
 */
class BackupRestoreDaoSemanticsTest {
    // region Tags

    @Test
    fun `tag merge updates by name and keeps the existing id`() =
        runTest {
            val dao = FakeTagDao()
            val existing = Tag(name = "work", color = "#111111")
            dao.insert(existing)

            val counts = dao.upsertTagsByName(listOf(Tag(name = "work", color = "#999999")))

            assertEquals(BackupUpsertCounts(inserted = 0, updated = 1), counts)
            val merged = dao.tags.single()
            assertEquals(existing.id, merged.id)
            assertEquals("#999999", merged.color)
        }

    @Test
    fun `tag merge inserts new tags and keeps the backup id when free`() =
        runTest {
            val dao = FakeTagDao()
            val incoming = Tag(name = "new-tag", color = null)

            val counts = dao.upsertTagsByName(listOf(incoming))

            assertEquals(BackupUpsertCounts(inserted = 1, updated = 0), counts)
            assertEquals(incoming.id, dao.tags.single().id)
        }

    @Test
    fun `tag merge regenerates the id on collision with a different tag`() =
        runTest {
            val dao = FakeTagDao()
            val collidingId = UUID.randomUUID()
            dao.insert(Tag(id = collidingId, name = "other"))

            dao.upsertTagsByName(listOf(Tag(id = collidingId, name = "fresh")))

            val inserted = dao.tags.single { it.name == "fresh" }
            assertNotEquals(collidingId, inserted.id)
            assertEquals(2, dao.tags.size)
        }

    @Test
    fun `tag replace clears existing tags first`() =
        runTest {
            val dao = FakeTagDao()
            dao.insert(Tag(name = "old-1"))
            dao.insert(Tag(name = "old-2"))
            val incoming = Tag(name = "from-backup")

            val counts = dao.replaceAllTags(listOf(incoming))

            assertEquals(BackupUpsertCounts(inserted = 1, updated = 0), counts)
            assertEquals(listOf(incoming), dao.tags)
        }

    // endregion

    // region Word replacements

    @Test
    fun `replacement merge updates by original and keeps the existing id`() =
        runTest {
            val dao = FakeWordReplacementDao()
            val existing = WordReplacement(original = "teh", replacement = "the")
            dao.insert(existing)

            val counts =
                dao.upsertReplacementsByOriginal(
                    listOf(
                        WordReplacement(original = "teh", replacement = "THE", caseSensitive = true, enabled = false),
                    ),
                )

            assertEquals(BackupUpsertCounts(inserted = 0, updated = 1), counts)
            val merged = dao.rows.single()
            assertEquals(existing.id, merged.id)
            assertEquals("THE", merged.replacement)
            assertTrue(merged.caseSensitive)
            assertEquals(false, merged.enabled)
        }

    @Test
    fun `replacement merge inserts unmatched rules`() =
        runTest {
            val dao = FakeWordReplacementDao()
            dao.insert(WordReplacement(original = "existing", replacement = "kept"))

            val counts =
                dao.upsertReplacementsByOriginal(listOf(WordReplacement(original = "brand-new", replacement = "x")))

            assertEquals(BackupUpsertCounts(inserted = 1, updated = 0), counts)
            assertEquals(2, dao.rows.size)
        }

    @Test
    fun `replacement replace clears existing rules first`() =
        runTest {
            val dao = FakeWordReplacementDao()
            dao.insert(WordReplacement(original = "old", replacement = "gone"))
            val incoming = WordReplacement(original = "only", replacement = "rule")

            dao.replaceAllReplacements(listOf(incoming))

            assertEquals(listOf(incoming), dao.rows)
        }

    // endregion

    // region Profiles

    @Test
    fun `profile merge keeps existing id and sort order and rewrites default tags`() =
        runTest {
            val dao = FakeProfileDao()
            val tagA = UUID.randomUUID()
            val tagB = UUID.randomUUID()
            dao.existingTagIds += listOf(tagA, tagB)
            val existing = Profile(name = "Meetings", sortOrder = 7, icon = "🎙️")
            dao.insert(existing)
            dao.defaultTags[existing.id] = mutableListOf(tagA)

            val incoming =
                ProfileBackupEntry(
                    profile = Profile(name = "Meetings", sortOrder = 99, icon = "📝", autoTitle = true),
                    defaultTagIds = listOf(tagB),
                )
            val counts = dao.upsertProfilesByName(listOf(incoming))

            assertEquals(BackupUpsertCounts(inserted = 0, updated = 1), counts)
            val merged = dao.profiles.single()
            assertEquals(existing.id, merged.id)
            assertEquals(7, merged.sortOrder)
            assertEquals("📝", merged.icon)
            assertTrue(merged.autoTitle)
            assertEquals(listOf(tagB), dao.defaultTags[existing.id])
        }

    @Test
    fun `profile merge appends new profiles after the current max sort order`() =
        runTest {
            val dao = FakeProfileDao()
            dao.insert(Profile(name = "Existing", sortOrder = 4))

            val incoming = ProfileBackupEntry(profile = Profile(name = "Imported", sortOrder = 0), defaultTagIds = emptyList())
            dao.upsertProfilesByName(listOf(incoming))

            val inserted = dao.profiles.single { it.name == "Imported" }
            assertEquals(5, inserted.sortOrder)
        }

    @Test
    fun `profile merge silently drops default tags that do not exist`() =
        runTest {
            val dao = FakeProfileDao()
            val knownTag = UUID.randomUUID()
            dao.existingTagIds += knownTag

            val incoming =
                ProfileBackupEntry(
                    profile = Profile(name = "New"),
                    defaultTagIds = listOf(knownTag, UUID.randomUUID()),
                )
            val counts = dao.upsertProfilesByName(listOf(incoming))

            assertEquals(BackupUpsertCounts(inserted = 1, updated = 0), counts)
            val profileId = dao.profiles.single().id
            assertEquals(listOf(knownTag), dao.defaultTags[profileId])
        }

    @Test
    fun `profile replace clears existing profiles and rewrites sort order by position`() =
        runTest {
            val dao = FakeProfileDao()
            dao.insert(Profile(name = "Old", sortOrder = 1))

            val first = ProfileBackupEntry(Profile(name = "A", sortOrder = 42), emptyList())
            val second = ProfileBackupEntry(Profile(name = "B", sortOrder = 7), emptyList())
            val counts = dao.replaceAllProfiles(listOf(first, second))

            assertEquals(BackupUpsertCounts(inserted = 2, updated = 0), counts)
            assertEquals(listOf("A", "B"), dao.profiles.sortedBy(Profile::sortOrder).map(Profile::name))
            assertEquals(listOf(0, 1), dao.profiles.sortedBy(Profile::sortOrder).map(Profile::sortOrder))
        }

    @Test
    fun `profile merge keeps backup id for new profiles unless it collides`() =
        runTest {
            val dao = FakeProfileDao()
            val collidingId = UUID.randomUUID()
            dao.insert(Profile(id = collidingId, name = "Original"))

            val freeId = UUID.randomUUID()
            dao.upsertProfilesByName(
                listOf(
                    ProfileBackupEntry(Profile(id = freeId, name = "Free Id"), emptyList()),
                    ProfileBackupEntry(Profile(id = collidingId, name = "Colliding Id"), emptyList()),
                ),
            )

            assertEquals(freeId, dao.profiles.single { it.name == "Free Id" }.id)
            assertNotEquals(collidingId, dao.profiles.single { it.name == "Colliding Id" }.id)
        }

    // endregion
}

// ---------------------------------------------------------------------------------------------
// In-memory fakes: only the abstract methods the default methods touch are implemented.
// ---------------------------------------------------------------------------------------------

private class FakeTagDao : TagDao {
    val tags = mutableListOf<Tag>()

    override suspend fun insert(tag: Tag) {
        check(tags.none { it.id == tag.id }) { "PK collision" }
        tags += tag
    }

    override suspend fun insertTags(tags: List<Tag>) {
        tags.forEach { insert(it) }
    }

    override suspend fun update(tag: Tag): Int {
        val index = tags.indexOfFirst { it.id == tag.id }
        if (index == -1) return 0
        tags[index] = tag
        return 1
    }

    override suspend fun getTag(id: UUID): Tag? = tags.firstOrNull { it.id == id }

    override suspend fun getTagByName(name: String): Tag? = tags.firstOrNull { it.name == name }

    override suspend fun deleteAllTags() = tags.clear()

    override fun getAllTags(): Flow<List<Tag>> = unused()

    override suspend fun getAllTagsList(): List<Tag> = tags.toList()

    override suspend fun getExistingTagIds(ids: List<UUID>): List<UUID> = tags.map(Tag::id).filter(ids::contains)

    override suspend fun getRecordingCount(recordingId: UUID): Int = unused()

    override suspend fun delete(tag: Tag) = unused()

    override suspend fun deleteById(id: UUID) = unused()

    override suspend fun getTagsForRecordingIds(recordingIds: List<UUID>): List<RecordingTagRow> = unused()

    override fun getTagsForRecordingIdsFlow(recordingIds: List<UUID>): Flow<List<RecordingTagRow>> = unused()

    override fun getTagsForRecording(recordingId: UUID): Flow<List<Tag>> = unused()

    override suspend fun getTagsForRecordingList(recordingId: UUID): List<Tag> = unused()

    override suspend fun addTagToRecording(recordingTag: RecordingTag) = unused()

    override suspend fun addTagsToRecording(tags: List<RecordingTag>) = unused()

    override suspend fun removeTagFromRecording(recordingTag: RecordingTag) = unused()

    override suspend fun removeAllTagsFromRecording(recordingId: UUID) = unused()

    override suspend fun removeTagFromRecordingById(
        recordingId: UUID,
        tagId: UUID,
    ) = unused()

    override suspend fun getCount(): Int = tags.size
}

private class FakeWordReplacementDao : WordReplacementDao {
    val rows = mutableListOf<WordReplacement>()

    override suspend fun insert(replacement: WordReplacement) {
        rows.removeAll { it.id == replacement.id } // @Insert(REPLACE) semantics
        rows += replacement
    }

    override suspend fun insertReplacements(replacements: List<WordReplacement>) {
        replacements.forEach { insert(it) }
    }

    override suspend fun update(replacement: WordReplacement) {
        val index = rows.indexOfFirst { it.id == replacement.id }
        if (index >= 0) rows[index] = replacement
    }

    override suspend fun getReplacement(id: UUID): WordReplacement? = rows.firstOrNull { it.id == id }

    override suspend fun getReplacementByOriginal(original: String): WordReplacement? =
        rows.firstOrNull { it.original == original }

    override suspend fun deleteAllReplacements() = rows.clear()

    override fun getAllReplacements(): Flow<List<WordReplacement>> = unused()

    override suspend fun getAllReplacementsList(): List<WordReplacement> = rows.toList()

    override suspend fun getEnabledReplacements(): List<WordReplacement> = unused()

    override suspend fun getEquivalentReplacement(
        original: String,
        replacement: String,
        caseSensitive: Boolean,
    ): WordReplacement? = unused()

    override suspend fun setEnabled(
        id: UUID,
        enabled: Boolean,
    ) = unused()

    override suspend fun delete(replacement: WordReplacement) = unused()

    override suspend fun deleteById(id: UUID) = unused()

    override suspend fun getCount(): Int = rows.size

    override suspend fun getEnabledCount(): Int = unused()
}

private class FakeProfileDao : ProfileDao {
    val profiles = mutableListOf<Profile>()
    val defaultTags = mutableMapOf<UUID, MutableList<UUID>>()
    val existingTagIds = mutableSetOf<UUID>()

    override suspend fun insert(profile: Profile) {
        check(profiles.none { it.id == profile.id }) { "PK collision" }
        profiles += profile
    }

    override suspend fun update(profile: Profile): Int {
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index == -1) return 0
        profiles[index] = profile
        return 1
    }

    override suspend fun getProfile(id: UUID): Profile? = profiles.firstOrNull { it.id == id }

    override suspend fun getProfileByName(name: String): Profile? = profiles.firstOrNull { it.name == name }

    override suspend fun getMaxSortOrder(): Int? = profiles.maxOfOrNull(Profile::sortOrder)

    override suspend fun deleteAllProfiles() {
        profiles.clear()
        defaultTags.clear()
    }

    override suspend fun getExistingTagIds(ids: List<UUID>): List<UUID> = ids.filter(existingTagIds::contains)

    override suspend fun insertDefaultTags(defaultTags: List<ProfileDefaultTag>) {
        defaultTags.forEach { link ->
            this.defaultTags.getOrPut(link.profileId) { mutableListOf() } += link.tagId
        }
    }

    override suspend fun deleteDefaultTagsForProfile(profileId: UUID) {
        defaultTags.remove(profileId)
    }

    override fun getAllProfiles(): Flow<List<Profile>> = unused()

    override suspend fun getAllProfilesList(): List<Profile> = profiles.toList()

    override suspend fun getProfiles(ids: List<UUID>): List<Profile> = unused()

    override fun getProfileFlow(id: UUID): Flow<Profile?> = unused()

    override suspend fun delete(profile: Profile) = unused()

    override suspend fun deleteById(id: UUID) = unused()

    override suspend fun getCount(): Int = profiles.size

    override fun getDefaultTagsForProfile(profileId: UUID): Flow<List<Tag>> = unused()

    override suspend fun getDefaultTagsForProfileList(profileId: UUID): List<Tag> = unused()

    override suspend fun getDefaultTagIds(profileId: UUID): List<UUID> =
        defaultTags[profileId].orEmpty().toList()

    override suspend fun getDefaultTagCount(profileId: UUID): Int = unused()
}

private fun unused(): Nothing = throw UnsupportedOperationException("not used by backup-restore semantics")
