package dev.chirpboard.app.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
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
        // Create oldest version of the database
        helper.createDatabase(TEST_DB, 1).apply {
            close()
        }

        // Run all migrations
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Room
            .databaseBuilder(
                context,
                AppDatabase::class.java,
                TEST_DB,
            ).addMigrations(*Migrations.ALL)
            .build()
            .apply {
                openHelper.writableDatabase.version
                close()
            }
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

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val migratedDb =
            Room
                .databaseBuilder(
                    context,
                    AppDatabase::class.java,
                    TEST_DB,
                ).addMigrations(Migrations.MIGRATION_2_3)
                .build()

        migratedDb.openHelper.writableDatabase.query(
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

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val migratedDb =
            Room
                .databaseBuilder(
                    context,
                    AppDatabase::class.java,
                    TEST_DB,
                ).addMigrations(Migrations.MIGRATION_3_4)
                .build()

        migratedDb.openHelper.writableDatabase.query(
            "SELECT rawText, summary FROM transcripts WHERE recordingId = '$recordingId'",
        ).use { cursor ->
            org.junit.Assert.assertTrue(cursor.moveToFirst())
            org.junit.Assert.assertEquals("hello world", cursor.getString(0))
            org.junit.Assert.assertEquals("summary", cursor.getString(1))
        }

        migratedDb.openHelper.writableDatabase.query(
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

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val migratedDb =
            Room
                .databaseBuilder(
                    context,
                    AppDatabase::class.java,
                    TEST_DB,
                ).addMigrations(Migrations.MIGRATION_4_5)
                .build()

        migratedDb.openHelper.writableDatabase.query(
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

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val migratedDb =
            Room
                .databaseBuilder(
                    context,
                    AppDatabase::class.java,
                    TEST_DB,
                ).addMigrations(Migrations.MIGRATION_5_6)
                .build()

        migratedDb.openHelper.writableDatabase.execSQL(
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

        migratedDb.openHelper.writableDatabase.query(
            "SELECT sourceTranscriptRevision, generationStatus, taskItemsPayload FROM structured_outcome_snapshots WHERE recordingId = '$recordingId'",
        ).use { cursor ->
            org.junit.Assert.assertTrue(cursor.moveToFirst())
            org.junit.Assert.assertEquals("rev-1", cursor.getString(0))
            org.junit.Assert.assertEquals("READY", cursor.getString(1))
            org.junit.Assert.assertEquals("UmV2aWV3IHRoZSBkcmFmdA==", cursor.getString(2))
        }

        migratedDb.close()
    }


    // Template for future migration tests:
    // Add new migration tests here as migrations are created...
}
