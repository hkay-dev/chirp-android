package dev.chirpboard.app

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Preferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val sharedPreferences = context.getSharedPreferences("chirp", Context.MODE_PRIVATE)

    /**
     * Reads a legacy plaintext API key once for migration to secure storage.
     */
    fun readLegacyGeminiApiKeyForMigration(): String =
        sharedPreferences.getString(KEY_GEMINI_API_KEY, "") ?: ""

    /**
     * Clears the API key from plaintext storage (used during migration).
     */
    fun clearGeminiApiKey() {
        sharedPreferences.edit().remove(KEY_GEMINI_API_KEY).apply()
    }

    /**
     * Latch for the one-time API key migration. Without it every process start rebuilt
     * EncryptedSharedPreferences (100-500 ms of Keystore work) just to learn the migration
     * already ran.
     */
    fun isApiKeyMigrationDone(): Boolean =
        sharedPreferences.getBoolean(KEY_API_KEY_MIGRATION_DONE, false)

    fun setApiKeyMigrationDone() {
        sharedPreferences.edit().putBoolean(KEY_API_KEY_MIGRATION_DONE, true).apply()
    }

    companion object {
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_API_KEY_MIGRATION_DONE = "api_key_migration_done"
    }
}
