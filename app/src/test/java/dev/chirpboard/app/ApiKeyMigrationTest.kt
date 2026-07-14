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
            every { preferences.readLegacyGeminiApiKeyForMigration() } returns "   "

            assertEquals(ApiKeyMigration.MigrationResult.NO_CUSTOM_KEY, migration.migrate())
        }

    @Test
    fun `migrate moves custom plaintext key into secure storage`() =
        runTest {
            every { llmPreferences.isSecureStorageAvailable() } returns true
            every { llmPreferences.hasApiKey() } returns false
            every { preferences.readLegacyGeminiApiKeyForMigration() } returns "user-custom-key"
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
    fun `isPlaceholder matches digests without storing plaintext keys`() {
        val placeholder = "test-only-placeholder"
        val digest = KnownGeminiPlaceholderKeys.sha256(placeholder)

        assertTrue(KnownGeminiPlaceholderKeys.isPlaceholder(""))
        assertTrue(KnownGeminiPlaceholderKeys.isPlaceholder(placeholder, setOf(digest)))
        assertTrue(KnownGeminiPlaceholderKeys.isPlaceholder("  $placeholder  ", setOf(digest)))
    }

    @Test
    fun `production placeholder digests are sha256 values`() {
        assertEquals(3, KnownGeminiPlaceholderKeys.PLACEHOLDER_KEY_SHA256.size)
        assertTrue(
            KnownGeminiPlaceholderKeys.PLACEHOLDER_KEY_SHA256.all { digest ->
                digest.matches(Regex("[0-9a-f]{64}"))
            },
        )
    }

    @Test
    fun `isPlaceholder rejects custom keys`() {
        assertFalse(KnownGeminiPlaceholderKeys.isPlaceholder("user-custom-key"))
    }
}
