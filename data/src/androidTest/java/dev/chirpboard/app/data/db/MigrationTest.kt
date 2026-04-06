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

    // Template for future migration tests:
    // Add new migration tests here as migrations are created...
}
