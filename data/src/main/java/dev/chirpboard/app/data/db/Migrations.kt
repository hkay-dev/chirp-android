package dev.chirpboard.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database migrations for AppDatabase.
 *
 * IMPORTANT: Never use fallbackToDestructiveMigration() - it deletes user data!
 * Always write explicit migrations.
 *
 * Migration naming: MIGRATION_X_Y where X is from version, Y is to version.
 *
 * To add a new migration:
 * 1. Create val MIGRATION_Y_Z = object : Migration(Y, Z) { ... }
 * 2. Add it to ALL_MIGRATIONS list
 * 3. Increment version in @Database annotation in AppDatabase.kt
 * 4. Write tests in MigrationTest.kt
 */
object Migrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_recordings_status_createdAt` ON `recordings` (`status`, `createdAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_recordings_profileId_createdAt` ON `recordings` (`profileId`, `createdAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_profiles_sortOrder_name` ON `profiles` (`sortOrder`, `name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_word_replacements_enabled_original` ON `word_replacements` (`enabled`, `original`)")
        }
    }

    /**
     * List of all migrations. Add new migrations here.
     * Order doesn't matter - Room sorts by version numbers.
     */
    val ALL: Array<Migration> =
        arrayOf(
            // Add migrations here as they're created, e.g.:
            MIGRATION_1_2,
            // MIGRATION_2_3,
        )

    // Example migration template (uncomment and modify when needed):
    /*
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Example: Add a new column with default value
            db.execSQL(
                "ALTER TABLE recordings ADD COLUMN new_field TEXT DEFAULT NULL"
            )
        }
    }
     */

    // Common migration patterns:
    //
    // 1. Add nullable column:
    //    db.execSQL("ALTER TABLE table_name ADD COLUMN column_name TYPE DEFAULT NULL")
    //
    // 2. Add non-null column with default:
    //    db.execSQL("ALTER TABLE table_name ADD COLUMN column_name TYPE NOT NULL DEFAULT value")
    //
    // 3. Create new table:
    //    db.execSQL("""
    //        CREATE TABLE IF NOT EXISTS new_table (
    //            id TEXT NOT NULL PRIMARY KEY,
    //            ...
    //        )
    //    """)
    //
    // 4. Create index:
    //    db.execSQL("CREATE INDEX IF NOT EXISTS index_name ON table_name(column_name)")
    //
    // 5. Rename table (SQLite 3.25+):
    //    db.execSQL("ALTER TABLE old_name RENAME TO new_name")
    //
    // 6. Complex changes (recreate table):
    //    - Create new table with desired schema
    //    - Copy data from old table
    //    - Drop old table
    //    - Rename new table to old name
    //    - Recreate indices and foreign keys

    // Future migrations will be added here...
}
