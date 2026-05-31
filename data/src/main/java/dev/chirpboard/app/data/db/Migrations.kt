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

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE profiles ADD COLUMN isQuickStartPinned INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `transcript_timings` (
                    `recordingId` TEXT NOT NULL,
                    `sequenceIndex` INTEGER NOT NULL,
                    `text` TEXT NOT NULL,
                    `startOffsetMs` INTEGER NOT NULL,
                    `endOffsetMs` INTEGER NOT NULL,
                    PRIMARY KEY(`recordingId`, `sequenceIndex`),
                    FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_transcript_timings_recordingId` ON `transcript_timings` (`recordingId`)"
            )
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE transcripts ADD COLUMN manualCorrectionText TEXT DEFAULT NULL"
            )
            db.execSQL(
                "ALTER TABLE transcripts ADD COLUMN manualCorrectionSourceText TEXT DEFAULT NULL"
            )
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `structured_outcome_snapshots` (
                    `recordingId` TEXT NOT NULL,
                    `sourceTranscriptRevision` TEXT,
                    `generationStatus` TEXT NOT NULL,
                    `generatedAt` INTEGER,
                    `lastAttemptedAt` INTEGER NOT NULL,
                    `failureMessage` TEXT,
                    `taskItemsPayload` TEXT,
                    `decisionItemsPayload` TEXT,
                    `followUpItemsPayload` TEXT,
                    PRIMARY KEY(`recordingId`),
                    FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `recording_enhancement_intents` (
                    `recordingId` TEXT NOT NULL,
                    `processingModeId` TEXT,
                    `autoTitle` INTEGER NOT NULL,
                    `autoSummary` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `lastAttemptedAt` INTEGER,
                    `lastErrorMessage` TEXT,
                    PRIMARY KEY(`recordingId`),
                    FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO `recording_enhancement_intents` (
                    `recordingId`,
                    `processingModeId`,
                    `autoTitle`,
                    `autoSummary`,
                    `createdAt`,
                    `lastAttemptedAt`,
                    `lastErrorMessage`
                )
                SELECT
                    `recordings`.`id`,
                    NULLIF(`profiles`.`defaultProcessingMode`, ''),
                    COALESCE(`profiles`.`autoTitle`, 0),
                    COALESCE(`profiles`.`autoSummary`, 0),
                    strftime('%s', 'now') * 1000,
                    NULL,
                    `recordings`.`errorMessage`
                FROM `recordings`
                LEFT JOIN `profiles` ON `recordings`.`profileId` = `profiles`.`id`
                WHERE `recordings`.`status` IN ('PENDING_ENHANCEMENT', 'ENHANCING')
                    AND (
                        NULLIF(`profiles`.`defaultProcessingMode`, '') IS NOT NULL
                        OR COALESCE(`profiles`.`autoTitle`, 0) = 1
                        OR COALESCE(`profiles`.`autoSummary`, 0) = 1
                    )
                """.trimIndent(),
            )
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TEMP TABLE IF NOT EXISTS `profile_default_tags_migration` (
                    `profileId` TEXT NOT NULL,
                    `tagId` TEXT NOT NULL,
                    PRIMARY KEY(`profileId`, `tagId`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                WITH RECURSIVE split_default_tags(`profileId`, `tagId`, `rest`) AS (
                    SELECT
                        `id`,
                        '',
                        COALESCE(`defaultTagIds`, '') || ','
                    FROM `profiles`
                    WHERE `defaultTagIds` IS NOT NULL
                        AND TRIM(`defaultTagIds`) != ''
                    UNION ALL
                    SELECT
                        `profileId`,
                        TRIM(SUBSTR(`rest`, 0, INSTR(`rest`, ','))),
                        SUBSTR(`rest`, INSTR(`rest`, ',') + 1)
                    FROM split_default_tags
                    WHERE `rest` != ''
                )
                INSERT OR IGNORE INTO `profile_default_tags_migration` (`profileId`, `tagId`)
                SELECT split_default_tags.`profileId`, split_default_tags.`tagId`
                FROM split_default_tags
                INNER JOIN `tags` ON `tags`.`id` = split_default_tags.`tagId`
                WHERE split_default_tags.`tagId` != ''
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `profiles_new` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `icon` TEXT,
                    `defaultProcessingMode` TEXT,
                    `autoTranscribe` INTEGER NOT NULL,
                    `autoTitle` INTEGER NOT NULL,
                    `autoSummary` INTEGER NOT NULL,
                    `obsidianVaultPath` TEXT,
                    `autoExportToObsidian` INTEGER NOT NULL,
                    `sortOrder` INTEGER NOT NULL,
                    `isQuickStartPinned` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `profiles_new` (
                    `id`,
                    `name`,
                    `icon`,
                    `defaultProcessingMode`,
                    `autoTranscribe`,
                    `autoTitle`,
                    `autoSummary`,
                    `obsidianVaultPath`,
                    `autoExportToObsidian`,
                    `sortOrder`,
                    `isQuickStartPinned`
                )
                SELECT
                    `id`,
                    `name`,
                    `icon`,
                    `defaultProcessingMode`,
                    `autoTranscribe`,
                    `autoTitle`,
                    `autoSummary`,
                    `obsidianVaultPath`,
                    `autoExportToObsidian`,
                    `sortOrder`,
                    `isQuickStartPinned`
                FROM `profiles`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `profiles`")
            db.execSQL("ALTER TABLE `profiles_new` RENAME TO `profiles`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_profiles_sortOrder_name` ON `profiles` (`sortOrder`, `name`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `profile_default_tags` (
                    `profileId` TEXT NOT NULL,
                    `tagId` TEXT NOT NULL,
                    PRIMARY KEY(`profileId`, `tagId`),
                    FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_profile_default_tags_tagId` ON `profile_default_tags` (`tagId`)"
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO `profile_default_tags` (`profileId`, `tagId`)
                SELECT `profileId`, `tagId`
                FROM `profile_default_tags_migration`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `profile_default_tags_migration`")
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE recordings ADD COLUMN transcriptionExecutionToken TEXT DEFAULT NULL")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `recording_enhancement_snapshots` (
                    `recordingId` TEXT NOT NULL,
                    `schemaVersion` INTEGER NOT NULL,
                    `sourceTranscriptRevision` TEXT NOT NULL,
                    `sourceProcessedTextRevision` TEXT,
                    `processingModeRequested` INTEGER NOT NULL,
                    `processingModeId` TEXT,
                    `processingModeLabel` TEXT,
                    `processingModeType` TEXT,
                    `processingModePrompt` TEXT,
                    `processingModeStatus` TEXT NOT NULL,
                    `processingModeErrorMessage` TEXT,
                    `titleRequested` INTEGER NOT NULL,
                    `titleStatus` TEXT NOT NULL,
                    `titleErrorMessage` TEXT,
                    `summaryRequested` INTEGER NOT NULL,
                    `summaryStatus` TEXT NOT NULL,
                    `summaryErrorMessage` TEXT,
                    `llmProviderId` TEXT,
                    `llmModelId` TEXT,
                    `activeEnhancementExecutionToken` TEXT,
                    `legacyRequiresResolution` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `lastAttemptedAt` INTEGER,
                    `lastErrorMessage` TEXT,
                    PRIMARY KEY(`recordingId`),
                    FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO `recording_enhancement_snapshots` (
                    `recordingId`,
                    `schemaVersion`,
                    `sourceTranscriptRevision`,
                    `sourceProcessedTextRevision`,
                    `processingModeRequested`,
                    `processingModeId`,
                    `processingModeLabel`,
                    `processingModeType`,
                    `processingModePrompt`,
                    `processingModeStatus`,
                    `processingModeErrorMessage`,
                    `titleRequested`,
                    `titleStatus`,
                    `titleErrorMessage`,
                    `summaryRequested`,
                    `summaryStatus`,
                    `summaryErrorMessage`,
                    `llmProviderId`,
                    `llmModelId`,
                    `activeEnhancementExecutionToken`,
                    `legacyRequiresResolution`,
                    `createdAt`,
                    `lastAttemptedAt`,
                    `lastErrorMessage`
                )
                SELECT
                    `recordings`.`id`,
                    1,
                    COALESCE(`transcripts`.`rawText`, '') || '|' ||
                        COALESCE(`transcripts`.`manualCorrectionText`, '') || '|' ||
                        COALESCE(`transcripts`.`manualCorrectionSourceText`, ''),
                    CASE
                        WHEN `transcripts`.`processedText` IS NULL THEN NULL
                        ELSE COALESCE(`transcripts`.`processingMode`, '') || '|' || `transcripts`.`processedText`
                    END,
                    CASE WHEN NULLIF(`recording_enhancement_intents`.`processingModeId`, '') IS NOT NULL THEN 1 ELSE 0 END,
                    NULLIF(`recording_enhancement_intents`.`processingModeId`, ''),
                    NULLIF(`recording_enhancement_intents`.`processingModeId`, ''),
                    'LEGACY_INTENT',
                    NULL,
                    CASE
                        WHEN NULLIF(`recording_enhancement_intents`.`processingModeId`, '') IS NULL THEN 'SKIPPED'
                        WHEN `recordings`.`status` = 'FAILED' THEN 'FAILED'
                        ELSE 'PENDING'
                    END,
                    CASE
                        WHEN NULLIF(`recording_enhancement_intents`.`processingModeId`, '') IS NOT NULL
                            AND `recordings`.`status` = 'FAILED'
                        THEN `recording_enhancement_intents`.`lastErrorMessage`
                        ELSE NULL
                    END,
                    `recording_enhancement_intents`.`autoTitle`,
                    CASE
                        WHEN `recording_enhancement_intents`.`autoTitle` = 0 THEN 'SKIPPED'
                        WHEN `recordings`.`status` = 'FAILED' THEN 'FAILED'
                        ELSE 'PENDING'
                    END,
                    CASE
                        WHEN `recording_enhancement_intents`.`autoTitle` = 1
                            AND `recordings`.`status` = 'FAILED'
                        THEN `recording_enhancement_intents`.`lastErrorMessage`
                        ELSE NULL
                    END,
                    `recording_enhancement_intents`.`autoSummary`,
                    CASE
                        WHEN `recording_enhancement_intents`.`autoSummary` = 0 THEN 'SKIPPED'
                        WHEN `recordings`.`status` = 'FAILED' THEN 'FAILED'
                        ELSE 'PENDING'
                    END,
                    CASE
                        WHEN `recording_enhancement_intents`.`autoSummary` = 1
                            AND `recordings`.`status` = 'FAILED'
                        THEN `recording_enhancement_intents`.`lastErrorMessage`
                        ELSE NULL
                    END,
                    NULL,
                    NULL,
                    NULL,
                    CASE
                        WHEN NULLIF(`recording_enhancement_intents`.`processingModeId`, '') IS NULL
                            AND `recording_enhancement_intents`.`autoTitle` = 0
                            AND `recording_enhancement_intents`.`autoSummary` = 0
                        THEN 1
                        ELSE 0
                    END,
                    `recording_enhancement_intents`.`createdAt`,
                    `recording_enhancement_intents`.`lastAttemptedAt`,
                    `recording_enhancement_intents`.`lastErrorMessage`
                FROM `recording_enhancement_intents`
                INNER JOIN `recordings` ON `recordings`.`id` = `recording_enhancement_intents`.`recordingId`
                LEFT JOIN `transcripts` ON `transcripts`.`recordingId` = `recordings`.`id`
                WHERE `recordings`.`status` IN ('PENDING_ENHANCEMENT', 'ENHANCING', 'FAILED')
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO `recording_enhancement_snapshots` (
                    `recordingId`,
                    `schemaVersion`,
                    `sourceTranscriptRevision`,
                    `sourceProcessedTextRevision`,
                    `processingModeRequested`,
                    `processingModeId`,
                    `processingModeLabel`,
                    `processingModeType`,
                    `processingModePrompt`,
                    `processingModeStatus`,
                    `processingModeErrorMessage`,
                    `titleRequested`,
                    `titleStatus`,
                    `titleErrorMessage`,
                    `summaryRequested`,
                    `summaryStatus`,
                    `summaryErrorMessage`,
                    `llmProviderId`,
                    `llmModelId`,
                    `activeEnhancementExecutionToken`,
                    `legacyRequiresResolution`,
                    `createdAt`,
                    `lastAttemptedAt`,
                    `lastErrorMessage`
                )
                SELECT
                    `recordings`.`id`,
                    1,
                    COALESCE(`transcripts`.`rawText`, '') || '|' ||
                        COALESCE(`transcripts`.`manualCorrectionText`, '') || '|' ||
                        COALESCE(`transcripts`.`manualCorrectionSourceText`, ''),
                    CASE
                        WHEN `transcripts`.`processedText` IS NULL THEN NULL
                        ELSE COALESCE(`transcripts`.`processingMode`, '') || '|' || `transcripts`.`processedText`
                    END,
                    CASE WHEN NULLIF(`profiles`.`defaultProcessingMode`, '') IS NOT NULL THEN 1 ELSE 0 END,
                    NULLIF(`profiles`.`defaultProcessingMode`, ''),
                    NULLIF(`profiles`.`defaultProcessingMode`, ''),
                    CASE WHEN `profiles`.`id` IS NULL THEN 'LEGACY_PROFILELESS' ELSE 'LEGACY_PROFILE' END,
                    NULL,
                    CASE WHEN NULLIF(`profiles`.`defaultProcessingMode`, '') IS NOT NULL THEN 'PENDING' ELSE 'SKIPPED' END,
                    NULL,
                    COALESCE(`profiles`.`autoTitle`, 0),
                    CASE WHEN COALESCE(`profiles`.`autoTitle`, 0) = 1 THEN 'PENDING' ELSE 'SKIPPED' END,
                    NULL,
                    COALESCE(`profiles`.`autoSummary`, 0),
                    CASE WHEN COALESCE(`profiles`.`autoSummary`, 0) = 1 THEN 'PENDING' ELSE 'SKIPPED' END,
                    NULL,
                    NULL,
                    NULL,
                    NULL,
                    CASE
                        WHEN `profiles`.`id` IS NULL THEN 1
                        WHEN NULLIF(`profiles`.`defaultProcessingMode`, '') IS NULL
                            AND COALESCE(`profiles`.`autoTitle`, 0) = 0
                            AND COALESCE(`profiles`.`autoSummary`, 0) = 0
                        THEN 1
                        ELSE 0
                    END,
                    strftime('%s', 'now') * 1000,
                    NULL,
                    `recordings`.`errorMessage`
                FROM `recordings`
                LEFT JOIN `profiles` ON `recordings`.`profileId` = `profiles`.`id`
                LEFT JOIN `transcripts` ON `transcripts`.`recordingId` = `recordings`.`id`
                WHERE `recordings`.`status` IN ('PENDING_ENHANCEMENT', 'ENHANCING')
                    AND NOT EXISTS (
                        SELECT 1 FROM `recording_enhancement_snapshots`
                        WHERE `recording_enhancement_snapshots`.`recordingId` = `recordings`.`id`
                    )
                """.trimIndent(),
            )
        }
    }

    /**
     * List of all migrations. Add new migrations here.
     * Order doesn't matter - Room sorts by version numbers.
     */
    val ALL: Array<Migration> =
        arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
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
