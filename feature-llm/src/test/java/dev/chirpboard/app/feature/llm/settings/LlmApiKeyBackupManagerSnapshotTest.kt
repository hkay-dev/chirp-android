package dev.chirpboard.app.feature.llm.settings

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-memory snapshot path added for the unified Backup & Restore flow. It must produce
 * the exact same CHIRPKEY container as the standalone .chirpkeys export (same codec, same
 * payload), validate fully before applying, and never write on a failed decrypt.
 */
class LlmApiKeyBackupManagerSnapshotTest {
    private val context = mockk<Context>(relaxed = true)

    private val snapshot =
        LlmSettingsSnapshot(
            activeProvider = "gemini",
            models = mapOf("gemini" to "gemini-3.1-flash-lite"),
            apiKeys = mapOf("gemini" to "secret-gemini", "openai" to "secret-openai"),
        )

    private fun managerWith(preferences: LlmPreferences): LlmApiKeyBackupManager = LlmApiKeyBackupManager(context, preferences)

    @Test
    fun `build and restore round trip applies the same snapshot`() =
        runTest {
            val preferences = mockk<LlmPreferences>()
            every { preferences.isSecureStorageAvailable() } returns true
            every { preferences.buildSettingsSnapshot() } returns snapshot
            val applied = slot<LlmSettingsSnapshot>()
            coEvery { preferences.applySettingsSnapshot(capture(applied)) } returns Unit
            val manager = managerWith(preferences)
            val passphrase = "round-trip-passphrase".toCharArray()

            val backup = manager.buildEncryptedSnapshot(passphrase).getOrThrow()
            assertEquals(2, backup.keyCount)

            val restoredCount =
                manager.restoreEncryptedSnapshot(backup.bytes, "round-trip-passphrase".toCharArray()).getOrThrow()

            assertEquals(2, restoredCount)
            assertEquals(snapshot, applied.captured)
        }

    @Test
    fun `restore with the wrong passphrase fails without applying anything`() =
        runTest {
            val preferences = mockk<LlmPreferences>()
            every { preferences.isSecureStorageAvailable() } returns true
            every { preferences.buildSettingsSnapshot() } returns snapshot
            val manager = managerWith(preferences)

            val backup = manager.buildEncryptedSnapshot("correct-passphrase".toCharArray()).getOrThrow()
            val result = manager.restoreEncryptedSnapshot(backup.bytes, "wrong-passphrase".toCharArray())

            assertTrue(result.isFailure)
            coVerify(exactly = 0) { preferences.applySettingsSnapshot(any()) }
        }

    @Test
    fun `restore of garbage bytes fails without applying anything`() =
        runTest {
            val preferences = mockk<LlmPreferences>()
            every { preferences.isSecureStorageAvailable() } returns true
            val manager = managerWith(preferences)

            val result = manager.restoreEncryptedSnapshot("not-chirpkey-data".toByteArray(), "whatever-pass".toCharArray())

            assertTrue(result.isFailure)
            coVerify(exactly = 0) { preferences.applySettingsSnapshot(any()) }
        }

    @Test
    fun `build fails when no keys are saved`() =
        runTest {
            val preferences = mockk<LlmPreferences>()
            every { preferences.isSecureStorageAvailable() } returns true
            every { preferences.buildSettingsSnapshot() } returns snapshot.copy(apiKeys = emptyMap())
            val manager = managerWith(preferences)

            val result = manager.buildEncryptedSnapshot("some-passphrase".toCharArray())

            assertTrue(result.isFailure)
        }

    @Test
    fun `build fails when secure storage is unavailable`() =
        runTest {
            val preferences = mockk<LlmPreferences>()
            every { preferences.isSecureStorageAvailable() } returns false
            val manager = managerWith(preferences)

            val result = manager.buildEncryptedSnapshot("some-passphrase".toCharArray())

            assertTrue(result.isFailure)
        }
}
