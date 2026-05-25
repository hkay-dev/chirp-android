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

    companion object {
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
    }
}
