package dev.chirpboard.app.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

/**
 * Tests for database migrations.
 *
 * Run these tests after creating any new migration to ensure:
 * 1. Migration SQL is valid
 * 2. Data is preserved during migration
 * 3. Schema matches expected state after migration
 *
 * To run: ./gradlew :data:connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
        )

    /**
     * Test that the current database can be created and is valid.
     * This serves as a baseline test - if this fails, something is
     * fundamentally wrong with the database setup.
     */
    @Test
    @Throws(IOException::class)
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db =
            Room
                .databaseBuilder(
                    context,
                    AppDatabase::class.java,
                    TEST_DB,
                ).build()

        // Verify database is accessible by getting DAOs
        db.recordingDao()
        db.transcriptDao()
        db.structuredOutcomeSnapshotDao()
        db.recordingEnhancementIntentDao()
        db.recordingEnhancementSnapshotDao()
        db.profileDao()
        db.tagDao()
        db.wordReplacementDao()

        db.close()
    }

    /**
     * Test that all migrations can be applied successfully.
     * This validates the complete migration path from version 1 to current.
     */
    @Test
    @Throws(IOException::class)
    fun migrateAll() {
        val profileId = UUID.randomUUID().toString()
        val tagId = UUID.randomUUID().toString()
        val recordingId = UUID.randomUUID().toString()
        val transcriptId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()

        helper.createDatabase(TEST_DB, 1).apply {
            execSQL("INSERT INTO tags(id, name, color) VALUES('$tagId', 'Migrated', '#333333')")
            execSQL(
                """
                INSERT INTO profiles(
                    id,
                    name,
                    icon,
                    defaultProcessingMode,
                    autoTranscribe,
                    autoTitle,
                    autoSummary,
                    obsidianVaultPath,
                    autoExportToObsidian,
                    defaultTagIds,
                    sortOrder
                ) VALUES(
                    '$profileId',
                    'Legacy profile',
                    NULL,
                    'cleanup',
                    1,
                    1,
                    0,
                    NULL,
                    0,
                    '$tagId',
                    1
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO recordings(
                    id,
                    title,
                    audioPath,
                    status,
                    source,
                    profileId,
                    createdAt,
                    durationMs,
                    errorMessage,
                    lastExportedPath,
                    lastExportedAt
                ) VALUES(
                    '$recordingId',
                    'Legacy recording',
                    '/tmp/legacy.m4a',
                    'PENDING_ENHANCEMENT',
                    'APP',
                    '$profileId',
                    $createdAt,
                    1200,
                    NULL,
                    NULL,
                    NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO transcripts(
                    id,
                    recordingId,
                    rawText,
                    processedText,
                    processingMode,
                    summary,
                    createdAt,
                    updatedAt
                ) VALUES(
                    '$transcriptId',
                    '$recordingId',
                    'legacy raw',
                    NULL,
                    NULL,
                    'legacy summary',
                    $createdAt,
                    $createdAt
                )
                """.trimIndent(),
            )
            execSQL("INSERT INTO recording_tags(recordingId, tagId) VALUES('$recordingId', '$tagId')")
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 9, true, *Migrations.ALL)
        migratedDb.query(
            """
            SELECT recordings.title, transcripts.rawText, tags.name
            FROM recordings
            INNER JOIN transcripts ON transcripts.recordingId = recordings.id
            INNER JOIN recording_tags ON recording_tags.recordingId = recordings.id
            INNER JOIN tags ON tags.id = recording_tags.tagId
            WHERE recordings.id = '$recordingId'
            """.trimIndent(),
        ).use { cursor ->
            org.junit.Assert.assertTrue(cursor.moveToFirst())
            org.junit.Assert.assertEquals("Legacy recording", cursor.getString(0))
            org.junit.Assert.assertEquals("legacy raw", cursor.getString(1))
            org.junit.Assert.assertEquals("Migrated", cursor.getString(2))
        }
        migratedDb.query(
            "SELECT COUNT(*) FROM profile_default_tags WHERE profileId = '$profileId' AND tagId = '$tagId'",
        ).use { cursor ->
            org.junit.Assert.assertTrue(cursor.moveToFirst())
            org.junit.Assert.assertEquals(1, cursor.getInt(0))
        }
        migratedDb.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate2To3_addsQuickStartPinColumnWithDefault() {
        val profileId = UUID.randomUUID().toString()
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                """
                INSERT INTO profiles(
                    id,
                    name,
                    icon,
                    defaultProcessingMode,
                    autoTranscribe,
                    autoTitle,
                    autoSummary,
                    obsidianVaultPath,
                    autoExportToObsidian,
                    defaultTagIds,
                    sortOrder
                ) VALUES(
                    '$profileId',
                    'Pinned later',
                    NULL,
                    NULL,
                    1,
                    0,
                    0,
                    NULL,
                    0,
                    NULL,
                    7
                )
                """.trimIndent(),
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 3, true, Migrations.MIGRATION_2_3)

        migratedDb.query(
            "SELECT isQuickStartPinned FROM profiles WHERE id = '$profileId'",
        ).use { cursor ->
            org.junit.Assert.assertTrue(cursor.moveToFirst())
            org.junit.Assert.assertEquals(0, cursor.getInt(0))
        }
        migratedDb.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate3To4_preservesTranscriptAndCreatesEmptyTimingRows() {
        val recordingId = UUID.randomUUID().toString()
        val transcriptId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()

        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                """
                INSERT INTO recordings(
                    id,
                    title,
                    audioPath,
                    status,
                    source,
                    profileId,
                    createdAt,
                    durationMs,
                    errorMessage,
                    lastExportedPath,
                    lastExportedAt
                ) VALUES(
                    '$recordingId',
                    'Timed later',
                    '/tmp/audio.m4a',
                    'COMPLETED',
                    'APP',
                    NULL,
                    $createdAt,
                    4200,
                    NULL,
                    NULL,
                    NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO transcripts(
                    id,
                    recordingId,
                    rawText,
                    processedText,
                    processingMode,
                    summary,
                    createdAt,
                    updatedAt
                ) VALUES(
                    '$transcriptId',
                    '$recordingId',
                    'hello world',
                    NULL,
                    NULL,
                    'summary',
                    $createdAt,
                    $createdAt
                )
                """.trimIndent(),
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 4, true, Migrations.MIGRATION_3_4)

        migratedDb.query(
            "SELECT rawText, summary FROM transcripts WHERE recordingId = '$recordingId'",
        ).use { cursor ->
            org.junit.Assert.assertTrue(cursor.moveToFirst())
            org.junit.Assert.assertEquals("hello world", cursor.getString(0))
            org.junit.Assert.assertEquals("summary", cursor.getString(1))
        }

        migratedDb.query(
            "SELECT COUNT(*) FROM transcript_timings WHERE recordingId = '$recordingId'",
        ).use { cursor ->
            org.junit.Assert.assertTrue(cursor.moveToFirst())
            org.junit.Assert.assertEquals(0, cursor.getInt(0))
        }

        migratedDb.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate4To5_preservesTranscriptAndAddsManualCorrectionColumns() {
        val recordingId = UUID.randomUUID().toString()
        val transcriptId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()

        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                """
                INSERT INTO recordings(
                    id,
                    title,
                    audioPath,
                    status,
                    source,
                    profileId,
                    createdAt,
                    durationMs,
                    errorMessage,
                    lastExportedPath,
                    lastExportedAt
                ) VALUES(
                    '$recordingId',
                    'Correction later',
                    '/tmp/audio.m4a',
                    'COMPLETED',
                    'APP',
                    NULL,
                    $createdAt,
                    4200,
                    NULL,
                    NULL,
                    NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO transcripts(
                    id,
                    recordingId,
                    rawText,
                    processedText,
                    processingMode,
                    summary,
                    createdAt,
                    updatedAt
                ) VALUES(
                    '$transcriptId',
                    '$recordingId',
                    'raw transcript',
                    'processed transcript',
                    'word_replacement',
                    'summary',
                    $createdAt,
                    $createdAt
                )
                """.trimIndent(),
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 5, true, Migrations.MIGRATION_4_5)

        migratedDb.query(
            "SELECT rawText, processedText, manualCorrectionText, manualCorrectionSourceText FROM transcripts WHERE recordingId = '$recordingId'",
        ).use { cursor ->
            org.junit.Assert.assertTrue(cursor.moveToFirst())
            org.junit.Assert.assertEquals("raw transcript", cursor.getString(0))
            org.junit.Assert.assertEquals("processed transcript", cursor.getString(1))
            org.junit.Assert.assertTrue(cursor.isNull(2))
            org.junit.Assert.assertTrue(cursor.isNull(3))
        }

        migratedDb.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate5To6_createsStructuredOutcomeSnapshotTable() {
        val recordingId = UUID.randomUUID().toString()
        val transcriptId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()

        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(
                """
                INSERT INTO recordings(
                    id,
                    title,
                    audioPath,
                    status,
                    source,
                    profileId,
                    createdAt,
                    durationMs,
                    errorMessage,
                    lastExportedPath,
                    lastExportedAt
                ) VALUES(
                    '$recordingId',
                    'Structured later',
                    '/tmp/audio.m4a',
                    'COMPLETED',
                    'APP',
                    NULL,
                    $createdAt,
                    4200,
                    NULL,
                    NULL,
                    NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO transcripts(
                    id,
                    recordingId,
                    rawText,
                    processedText,
                    processingMode,
                    manualCorrectionText,
                    manualCorrectionSourceText,
                    summary,
                    createdAt,
                    updatedAt
                ) VALUES(
                    '$transcriptId',
                    '$recordingId',
                    'raw transcript',
                    'processed transcript',
                    'word_replacement',
                    NULL,
                    NULL,
                    'summary',
                    $createdAt,
                    $createdAt
                )
                """.trimIndent(),
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 6, true, Migrations.MIGRATION_5_6)

        migratedDb.execSQL(
            """
            INSERT INTO structured_outcome_snapshots(
                recordingId,
                sourceTranscriptRevision,
                generationStatus,
                generatedAt,
                lastAttemptedAt,
                failureMessage,
                taskItemsPayload,
                decisionItemsPayload,
                followUpItemsPayload
            ) VALUES(
                '$recordingId',
                'rev-1',
                'READY',
                $createdAt,
                $createdAt,
                NULL,
                'UmV2aWV3IHRoZSBkcmFmdA==',
                NULL,
                NULL
            )
            """.trimIndent(),
        )

        migratedDb.query(
            "SELECT sourceTranscriptRevision, generationStatus, taskItemsPayload FROM structured_outcome_snapshots WHERE recordingId = '$recordingId'",
        ).use { cursor ->
            org.junit.Assert.assertTrue(cursor.moveToFirst())
            org.junit.Assert.assertEquals("rev-1", cursor.getString(0))
            org.junit.Assert.assertEquals("READY", cursor.getString(1))
            org.junit.Assert.assertEquals("UmV2aWV3IHRoZSBkcmFmdA==", cursor.getString(2))
        }

        migratedDb.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate6To7_createsEnhancementIntentTableAndBackfillsProfileRequests() {
        val profileId = UUID.randomUUID().toString()
        val recordingId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()

        helper.createDatabase(TEST_DB, 6).apply {
            execSQL(
                """
                INSERT INTO profiles(
                    id,
                    name,
                    icon,
                    defaultProcessingMode,
                    autoTranscribe,
                    autoTitle,
                    autoSummary,
                    obsidianVaultPath,
                    autoExportToObsidian,
                    defaultTagIds,
                    sortOrder,
                    isQuickStartPinned
                ) VALUES(
                    '$profileId',
                    'Enhance later',
                    NULL,
                    'cleanup',
                    1,
                    1,
                    0,
                    NULL,
                    0,
                    NULL,
                    1,
                    0
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO recordings(
                    id,
                    title,
                    audioPath,
                    status,
                    source,
                    profileId,
                    createdAt,
                    durationMs,
                    errorMessage,
                    lastExportedPath,
                    lastExportedAt
                ) VALUES(
                    '$recordingId',
                    'Pending enhancement',
                    '/tmp/audio.m4a',
                    'PENDING_ENHANCEMENT',
                    'APP',
                    '$profileId',
                    $createdAt,
                    4200,
                    'recoverable',
                    NULL,
                    NULL
                )
                """.trimIndent(),
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 7, true, Migrations.MIGRATION_6_7)

        migratedDb.query(
            """
            SELECT processingModeId, autoTitle, autoSummary, lastErrorMessage
            FROM recording_enhancement_intents
            WHERE recordingId = '$recordingId'
            """.trimIndent(),
        ).use { cursor ->
            org.junit.Assert.assertTrue(cursor.moveToFirst())
            org.junit.Assert.assertEquals("cleanup", cursor.getString(0))
            org.junit.Assert.assertEquals(1, cursor.getInt(1))
            org.junit.Assert.assertEquals(0, cursor.getInt(2))
            org.junit.Assert.assertEquals("recoverable", cursor.getString(3))
        }

        migratedDb.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate7To8_backfillsProfileDefaultTagsAndRemovesCsvColumn() {
        val profileId = UUID.randomUUID().toString()
        val validTagId = UUID.randomUUID().toString()
        val secondTagId = UUID.randomUUID().toString()
        val missingTagId = UUID.randomUUID().toString()

        helper.createDatabase(TEST_DB, 7).apply {
            execSQL(
                """
                INSERT INTO tags(id, name, color) VALUES('$validTagId', 'Alpha', '#111111')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO tags(id, name, color) VALUES('$secondTagId', 'Beta', '#222222')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO profiles(
                    id,
                    name,
                    icon,
                    defaultProcessingMode,
                    autoTranscribe,
                    autoTitle,
                    autoSummary,
                    obsidianVaultPath,
                    autoExportToObsidian,
                    defaultTagIds,
                    sortOrder,
                    isQuickStartPinned
                ) VALUES(
                    '$profileId',
                    'Defaults',
                    NULL,
                    NULL,
                    1,
                    0,
                    0,
                    NULL,
                    0,
                    '$validTagId, $missingTagId, $secondTagId, $validTagId',
                    1,
                    1
                )
                """.trimIndent(),
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 8, true, Migrations.MIGRATION_7_8)

        migratedDb.query(
            """
            SELECT tagId
            FROM profile_default_tags
            WHERE profileId = '$profileId'
            ORDER BY tagId ASC
            """.trimIndent(),
        ).use { cursor ->
            val migratedTagIds = mutableListOf<String>()
            while (cursor.moveToNext()) {
                migratedTagIds += cursor.getString(0)
            }
            org.junit.Assert.assertEquals(listOf(secondTagId, validTagId).sorted(), migratedTagIds)
        }

        migratedDb.query("PRAGMA table_info(`profiles`)").use { cursor ->
            val columnNames = mutableListOf<String>()
            while (cursor.moveToNext()) {
                columnNames += cursor.getString(1)
            }
            org.junit.Assert.assertFalse(columnNames.contains("defaultTagIds"))
        }

        migratedDb.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate8To9_createsSnapshotFromLegacyIntentForProfileBackedPending() {
        val profileId = UUID.randomUUID().toString()
        val recordingId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()

        helper.createDatabase(TEST_DB, 8).apply {
            insertVersion8Profile(
                profileId = profileId,
                defaultProcessingMode = "cleanup",
                autoTitle = 1,
                autoSummary = 0,
            )
            insertVersion8Recording(
                recordingId = recordingId,
                status = "PENDING_ENHANCEMENT",
                profileId = profileId,
                createdAt = createdAt,
                errorMessage = "queued",
            )
            insertVersion8Transcript(
                recordingId = recordingId,
                rawText = "raw transcript",
                processedText = "processed transcript",
                processingMode = "word_replacement",
                manualCorrectionText = "corrected",
                manualCorrectionSourceText = "raw transcript",
                createdAt = createdAt,
            )
            insertVersion8EnhancementIntent(
                recordingId = recordingId,
                processingModeId = "cleanup",
                autoTitle = 1,
                autoSummary = 0,
                createdAt = createdAt,
                lastErrorMessage = "queued",
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 9, true, Migrations.MIGRATION_8_9)

        migratedDb.query(
            """
            SELECT
                sourceTranscriptRevision,
                sourceProcessedTextRevision,
                processingModeRequested,
                processingModeId,
                processingModeLabel,
                processingModeType,
                processingModeStatus,
                titleRequested,
                titleStatus,
                summaryRequested,
                summaryStatus,
                legacyRequiresResolution,
                lastErrorMessage
            FROM recording_enhancement_snapshots
            WHERE recordingId = '$recordingId'
            """.trimIndent(),
        ).use { cursor ->
            org.junit.Assert.assertTrue(cursor.moveToFirst())
            org.junit.Assert.assertEquals("raw transcript|corrected|raw transcript", cursor.getString(0))
            org.junit.Assert.assertEquals("word_replacement|processed transcript", cursor.getString(1))
            org.junit.Assert.assertEquals(1, cursor.getInt(2))
            org.junit.Assert.assertEquals("cleanup", cursor.getString(3))
            org.junit.Assert.assertEquals("cleanup", cursor.getString(4))
            org.junit.Assert.assertEquals("LEGACY_INTENT", cursor.getString(5))
            org.junit.Assert.assertEquals("PENDING", cursor.getString(6))
            org.junit.Assert.assertEquals(1, cursor.getInt(7))
            org.junit.Assert.assertEquals("PENDING", cursor.getString(8))
            org.junit.Assert.assertEquals(0, cursor.getInt(9))
            org.junit.Assert.assertEquals("SKIPPED", cursor.getString(10))
            org.junit.Assert.assertEquals(0, cursor.getInt(11))
            org.junit.Assert.assertEquals("queued", cursor.getString(12))
        }

        migratedDb.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate8To9_backfillsProfilelessPendingEnhancementAsRecoverableSnapshot() {
        val recordingId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()

        helper.createDatabase(TEST_DB, 8).apply {
            insertVersion8Recording(
                recordingId = recordingId,
                status = "PENDING_ENHANCEMENT",
                profileId = null,
                createdAt = createdAt,
                errorMessage = "needs recovery",
            )
            insertVersion8Transcript(
                recordingId = recordingId,
                rawText = "profileless raw",
                processedText = null,
                processingMode = null,
                manualCorrectionText = null,
                manualCorrectionSourceText = null,
                summary = "existing summary",
                createdAt = createdAt,
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 9, true, Migrations.MIGRATION_8_9)

        migratedDb.query(
            """
            SELECT
                sourceTranscriptRevision,
                processingModeRequested,
                processingModeStatus,
                titleRequested,
                titleStatus,
                summaryRequested,
                summaryStatus,
                processingModeType,
                legacyRequiresResolution,
                lastErrorMessage
            FROM recording_enhancement_snapshots
            WHERE recordingId = '$recordingId'
            """.trimIndent(),
        ).use { cursor ->
            org.junit.Assert.assertTrue(cursor.moveToFirst())
            org.junit.Assert.assertEquals("profileless raw||", cursor.getString(0))
            org.junit.Assert.assertEquals(0, cursor.getInt(1))
            org.junit.Assert.assertEquals("SKIPPED", cursor.getString(2))
            org.junit.Assert.assertEquals(0, cursor.getInt(3))
            org.junit.Assert.assertEquals("SKIPPED", cursor.getString(4))
            org.junit.Assert.assertEquals(0, cursor.getInt(5))
            org.junit.Assert.assertEquals("SKIPPED", cursor.getString(6))
            org.junit.Assert.assertEquals("LEGACY_PROFILELESS", cursor.getString(7))
            org.junit.Assert.assertEquals(1, cursor.getInt(8))
            org.junit.Assert.assertEquals("needs recovery", cursor.getString(9))
        }

        migratedDb.query(
            "SELECT title, status, transcriptionExecutionToken FROM recordings WHERE id = '$recordingId'",
        ).use { cursor ->
            org.junit.Assert.assertTrue(cursor.moveToFirst())
            org.junit.Assert.assertEquals("Migrated recording", cursor.getString(0))
            org.junit.Assert.assertEquals("PENDING_ENHANCEMENT", cursor.getString(1))
            org.junit.Assert.assertTrue(cursor.isNull(2))
        }
        migratedDb.query(
            "SELECT rawText, summary FROM transcripts WHERE recordingId = '$recordingId'",
        ).use { cursor ->
            org.junit.Assert.assertTrue(cursor.moveToFirst())
            org.junit.Assert.assertEquals("profileless raw", cursor.getString(0))
            org.junit.Assert.assertEquals("existing summary", cursor.getString(1))
        }

        migratedDb.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate8To9_backfillsEnhancingRecordingFromProfileSettings() {
        val profileId = UUID.randomUUID().toString()
        val recordingId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()

        helper.createDatabase(TEST_DB, 8).apply {
            insertVersion8Profile(
                profileId = profileId,
                defaultProcessingMode = "formal",
                autoTitle = 0,
                autoSummary = 1,
            )
            insertVersion8Recording(
                recordingId = recordingId,
                status = "ENHANCING",
                profileId = profileId,
                createdAt = createdAt,
                errorMessage = null,
            )
            insertVersion8Transcript(
                recordingId = recordingId,
                rawText = "enhancing raw",
                processedText = null,
                processingMode = null,
                manualCorrectionText = null,
                manualCorrectionSourceText = null,
                createdAt = createdAt,
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 9, true, Migrations.MIGRATION_8_9)

        migratedDb.query(
            """
            SELECT
                processingModeRequested,
                processingModeId,
                processingModeType,
                processingModeStatus,
                titleRequested,
                titleStatus,
                summaryRequested,
                summaryStatus,
                legacyRequiresResolution
            FROM recording_enhancement_snapshots
            WHERE recordingId = '$recordingId'
            """.trimIndent(),
        ).use { cursor ->
            org.junit.Assert.assertTrue(cursor.moveToFirst())
            org.junit.Assert.assertEquals(1, cursor.getInt(0))
            org.junit.Assert.assertEquals("formal", cursor.getString(1))
            org.junit.Assert.assertEquals("LEGACY_PROFILE", cursor.getString(2))
            org.junit.Assert.assertEquals("PENDING", cursor.getString(3))
            org.junit.Assert.assertEquals(0, cursor.getInt(4))
            org.junit.Assert.assertEquals("SKIPPED", cursor.getString(5))
            org.junit.Assert.assertEquals(1, cursor.getInt(6))
            org.junit.Assert.assertEquals("PENDING", cursor.getString(7))
            org.junit.Assert.assertEquals(0, cursor.getInt(8))
        }

        migratedDb.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate8To9_preservesFailedLegacyIntentAsFailedSubwork() {
        val recordingId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()

        helper.createDatabase(TEST_DB, 8).apply {
            insertVersion8Recording(
                recordingId = recordingId,
                status = "FAILED",
                profileId = null,
                createdAt = createdAt,
                errorMessage = "llm down",
            )
            insertVersion8Transcript(
                recordingId = recordingId,
                rawText = "failed raw",
                processedText = null,
                processingMode = null,
                manualCorrectionText = null,
                manualCorrectionSourceText = null,
                createdAt = createdAt,
            )
            insertVersion8EnhancementIntent(
                recordingId = recordingId,
                processingModeId = "cleanup",
                autoTitle = 1,
                autoSummary = 1,
                createdAt = createdAt,
                lastAttemptedAt = createdAt + 1000,
                lastErrorMessage = "llm down",
            )
            close()
        }

        val migratedDb = helper.runMigrationsAndValidate(TEST_DB, 9, true, Migrations.MIGRATION_8_9)

        migratedDb.query(
            """
            SELECT
                processingModeStatus,
                processingModeErrorMessage,
                titleStatus,
                titleErrorMessage,
                summaryStatus,
                summaryErrorMessage,
                lastAttemptedAt,
                lastErrorMessage
            FROM recording_enhancement_snapshots
            WHERE recordingId = '$recordingId'
            """.trimIndent(),
        ).use { cursor ->
            org.junit.Assert.assertTrue(cursor.moveToFirst())
            org.junit.Assert.assertEquals("FAILED", cursor.getString(0))
            org.junit.Assert.assertEquals("llm down", cursor.getString(1))
            org.junit.Assert.assertEquals("FAILED", cursor.getString(2))
            org.junit.Assert.assertEquals("llm down", cursor.getString(3))
            org.junit.Assert.assertEquals("FAILED", cursor.getString(4))
            org.junit.Assert.assertEquals("llm down", cursor.getString(5))
            org.junit.Assert.assertEquals(createdAt + 1000, cursor.getLong(6))
            org.junit.Assert.assertEquals("llm down", cursor.getString(7))
        }

        migratedDb.close()
    }

    private fun SupportSQLiteDatabase.insertVersion8Profile(
        profileId: String,
        defaultProcessingMode: String?,
        autoTitle: Int,
        autoSummary: Int,
    ) {
        execSQL(
            """
            INSERT INTO profiles(
                id,
                name,
                icon,
                defaultProcessingMode,
                autoTranscribe,
                autoTitle,
                autoSummary,
                obsidianVaultPath,
                autoExportToObsidian,
                sortOrder,
                isQuickStartPinned
            ) VALUES(
                '$profileId',
                'Migrated profile',
                NULL,
                ${defaultProcessingMode.sqlString()},
                1,
                $autoTitle,
                $autoSummary,
                NULL,
                0,
                1,
                0
            )
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.insertVersion8Recording(
        recordingId: String,
        status: String,
        profileId: String?,
        createdAt: Long,
        errorMessage: String?,
    ) {
        execSQL(
            """
            INSERT INTO recordings(
                id,
                title,
                audioPath,
                status,
                source,
                profileId,
                createdAt,
                durationMs,
                errorMessage,
                lastExportedPath,
                lastExportedAt
            ) VALUES(
                '$recordingId',
                'Migrated recording',
                '/tmp/audio.m4a',
                '$status',
                'APP',
                ${profileId.sqlString()},
                $createdAt,
                1200,
                ${errorMessage.sqlString()},
                NULL,
                NULL
            )
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.insertVersion8Transcript(
        recordingId: String,
        rawText: String,
        processedText: String?,
        processingMode: String?,
        manualCorrectionText: String?,
        manualCorrectionSourceText: String?,
        summary: String? = null,
        createdAt: Long,
    ) {
        val transcriptId = UUID.randomUUID().toString()
        execSQL(
            """
            INSERT INTO transcripts(
                id,
                recordingId,
                rawText,
                processedText,
                processingMode,
                manualCorrectionText,
                manualCorrectionSourceText,
                summary,
                createdAt,
                updatedAt
            ) VALUES(
                '$transcriptId',
                '$recordingId',
                ${rawText.sqlString()},
                ${processedText.sqlString()},
                ${processingMode.sqlString()},
                ${manualCorrectionText.sqlString()},
                ${manualCorrectionSourceText.sqlString()},
                ${summary.sqlString()},
                $createdAt,
                $createdAt
            )
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.insertVersion8EnhancementIntent(
        recordingId: String,
        processingModeId: String?,
        autoTitle: Int,
        autoSummary: Int,
        createdAt: Long,
        lastAttemptedAt: Long? = null,
        lastErrorMessage: String?,
    ) {
        execSQL(
            """
            INSERT INTO recording_enhancement_intents(
                recordingId,
                processingModeId,
                autoTitle,
                autoSummary,
                createdAt,
                lastAttemptedAt,
                lastErrorMessage
            ) VALUES(
                '$recordingId',
                ${processingModeId.sqlString()},
                $autoTitle,
                $autoSummary,
                $createdAt,
                ${lastAttemptedAt?.toString() ?: "NULL"},
                ${lastErrorMessage.sqlString()}
            )
            """.trimIndent(),
        )
    }

    private fun String?.sqlString(): String =
        if (this == null) {
            "NULL"
        } else {
            "'${replace("'", "''")}'"
        }


    // Template for future migration tests:
    // Add new migration tests here as migrations are created...
}
