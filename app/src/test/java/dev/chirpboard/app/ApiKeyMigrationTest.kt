package dev.chirpboard.app

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

import kotlinx.coroutines.test.runTest
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import dev.chirpboard.app.feature.llm.settings.LlmSecurePreferences
import dev.chirpboard.app.feature.llm.client.ApiKeyMigration
class ApiKeyMigrationTest {
    private lateinit var preferences: LlmPreferences
    private lateinit var securePreferences: LlmSecurePreferences
    private lateinit var migration: ApiKeyMigration

    @Before
    fun setup() {
        preferences = mockk(relaxed = true)
        securePreferences = mockk(relaxed = true)
        migration = ApiKeyMigration(preferences, securePreferences)
    }

    @Test
    fun `migrate returns ENCRYPTION_UNAVAILABLE when secure storage unavailable`() {
        every { securePreferences.isAvailable() } returns false

        val result = migration.migrate()

        assertEquals(ApiKeyMigration.MigrationResult.ENCRYPTION_UNAVAILABLE, result)
        verify(exactly = 0) { preferences.geminiApiKey }
    }

    @Test
    fun `migrate returns ALREADY_MIGRATED when secure storage has key`() {
        every { securePreferences.isAvailable() } returns true
        every { securePreferences.hasApiKey() } returns true

        val result = migration.migrate()

        assertEquals(ApiKeyMigration.MigrationResult.ALREADY_MIGRATED, result)
        verify(exactly = 0) { preferences.geminiApiKey }
    }

    @Test
    fun `migrate returns NO_CUSTOM_KEY when old key is blank`() {
        every { securePreferences.isAvailable() } returns true
        every { securePreferences.hasApiKey() } returns false
        every { preferences.geminiApiKey } returns ""

        val result = migration.migrate()

        assertEquals(ApiKeyMigration.MigrationResult.NO_CUSTOM_KEY, result)
    }

    @Test
    fun `migrate returns NO_CUSTOM_KEY when old key is old default`() {
        every { securePreferences.isAvailable() } returns true
        every { securePreferences.hasApiKey() } returns false
        every { preferences.geminiApiKey } returns "[removed-google-api-key]"

        val result = migration.migrate()

        assertEquals(ApiKeyMigration.MigrationResult.NO_CUSTOM_KEY, result)
    }

    @Test
    fun `migrate returns NO_CUSTOM_KEY when old key is current default`() {
        every { securePreferences.isAvailable() } returns true
        every { securePreferences.hasApiKey() } returns false
        every { preferences.geminiApiKey } returns "[removed-google-api-key]"

        val result = migration.migrate()

        assertEquals(ApiKeyMigration.MigrationResult.NO_CUSTOM_KEY, result)
    }

    @Test
    fun `migrate returns SUCCESS and clears old key when custom key exists`() {
        every { securePreferences.isAvailable() } returns true
        every { securePreferences.hasApiKey() } returns false
        every { preferences.geminiApiKey } returns "custom-key"

        val result = migration.migrate()

        assertEquals(ApiKeyMigration.MigrationResult.SUCCESS, result)
        verify { securePreferences.geminiApiKey = "custom-key" }
        verify { preferences.clearGeminiApiKey() }
    }

    @Test
    fun `migrate returns FAILED on exception`() {
        every { securePreferences.isAvailable() } throws RuntimeException("Test exception")

        val result = migration.migrate()

        assertEquals(ApiKeyMigration.MigrationResult.FAILED, result)
    }
}
