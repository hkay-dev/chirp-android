package dev.chirpboard.app

import android.util.Log
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiKeyMigrationTest {
    private val preferences = mockk<Preferences>()
    private val llmPreferences = mockk<LlmPreferences>()
    private val migration = ApiKeyMigration(preferences, llmPreferences)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `migrate skips blank plaintext key`() =
        runTest {
            every { llmPreferences.isSecureStorageAvailable() } returns true
            every { llmPreferences.hasApiKey() } returns false
            every { preferences.geminiApiKey } returns "   "

            assertEquals(ApiKeyMigration.MigrationResult.NO_CUSTOM_KEY, migration.migrate())
        }

    @Test
    fun `migrate skips known placeholder keys`() =
        runTest {
            every { llmPreferences.isSecureStorageAvailable() } returns true
            every { llmPreferences.hasApiKey() } returns false
            every { preferences.geminiApiKey } returns "[removed-google-api-key]"

            assertEquals(ApiKeyMigration.MigrationResult.NO_CUSTOM_KEY, migration.migrate())
            coVerify(exactly = 0) { llmPreferences.setApiKey(any()) }
        }

    @Test
    fun `migrate moves custom plaintext key into secure storage`() =
        runTest {
            every { llmPreferences.isSecureStorageAvailable() } returns true
            every { llmPreferences.hasApiKey() } returns false
            every { preferences.geminiApiKey } returns "user-custom-key"
            coEvery { llmPreferences.setApiKey("user-custom-key") } just runs
            every { preferences.clearGeminiApiKey() } just runs

            assertEquals(ApiKeyMigration.MigrationResult.SUCCESS, migration.migrate())

            coVerify { llmPreferences.setApiKey("user-custom-key") }
            verify { preferences.clearGeminiApiKey() }
        }

    @Test
    fun `migrate returns already migrated when secure storage has key`() =
        runTest {
            every { llmPreferences.isSecureStorageAvailable() } returns true
            every { llmPreferences.hasApiKey() } returns true

            assertEquals(ApiKeyMigration.MigrationResult.ALREADY_MIGRATED, migration.migrate())
        }
}

class KnownGeminiPlaceholderKeysTest {
    @Test
    fun `isPlaceholder matches legacy defaults and blank keys`() {
        assertTrue(KnownGeminiPlaceholderKeys.isPlaceholder(""))
        assertTrue(KnownGeminiPlaceholderKeys.isPlaceholder("[removed-google-api-key]"))
        assertTrue(KnownGeminiPlaceholderKeys.isPlaceholder("[removed-google-api-key]"))
    }

    @Test
    fun `isPlaceholder rejects custom keys`() {
        assertFalse(KnownGeminiPlaceholderKeys.isPlaceholder("user-custom-key"))
    }
}
