package dev.chirpboard.app.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

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
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
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
        val db = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            TEST_DB
        ).build()

        // Verify database is accessible by getting DAOs
        db.recordingDao()
        db.transcriptDao()
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
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            TEST_DB
        ).addMigrations(*Migrations.ALL).build().apply {
            openHelper.writableDatabase.version
            close()
        }
    }

    // Template for future migration tests:
    /*
    @Test
    @Throws(IOException::class)
    fun migrate1To2() {
        // Create version 1 database with test data
        helper.createDatabase(TEST_DB, 1).apply {
            // Insert test data using raw SQL (must match v1 schema exactly)
            execSQL("""
                INSERT INTO recordings (id, title, audioPath, status, source, createdAt, durationMs)
                VALUES ('550e8400-e29b-41d4-a716-446655440000', 'Test Recording', '/path/to/audio.m4a', 'COMPLETED', 'APP', 1704067200000, 60000)
            """)
            close()
        }

        // Run migration from 1 to 2
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, Migrations.MIGRATION_1_2)

        // Verify data was preserved and new columns exist
        val cursor = db.query("SELECT * FROM recordings WHERE id = '550e8400-e29b-41d4-a716-446655440000'")
        assert(cursor.moveToFirst()) { "Test recording should exist after migration" }

        // Verify existing columns preserved
        val titleIndex = cursor.getColumnIndex("title")
        assert(cursor.getString(titleIndex) == "Test Recording") { "Title should be preserved" }

        // Verify new columns added by migration (example)
        // val newFieldIndex = cursor.getColumnIndex("new_field")
        // assert(newFieldIndex >= 0) { "new_field column should exist" }

        cursor.close()
        db.close()
    }
    */

    // Add new migration tests here as migrations are created...
}
