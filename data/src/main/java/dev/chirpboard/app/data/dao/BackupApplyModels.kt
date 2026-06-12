package dev.chirpboard.app.data.dao

import dev.chirpboard.app.data.entity.Profile
import java.util.UUID

/**
 * Outcome of a backup-restore write against one section (tags / profiles / word replacements).
 *
 * `inserted` counts brand-new rows, `updated` counts rows that matched an existing record by
 * natural key (tag name, profile name, replacement original) and were updated in place.
 */
data class BackupUpsertCounts(
    val inserted: Int,
    val updated: Int,
) {
    val total: Int get() = inserted + updated
}

/**
 * One profile from a backup file together with its default-tag references, already resolved to
 * tag ids on the target device (names are matched by the backup engine before the DAO call).
 */
data class ProfileBackupEntry(
    val profile: Profile,
    val defaultTagIds: List<UUID>,
)
