package dev.chirpboard.app

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Migrates API key from plaintext SharedPreferences to encrypted storage.
 */
@Singleton
class ApiKeyMigration @Inject constructor(
    private val preferences: Preferences,
    private val llmPreferences: dev.chirpboard.app.feature.llm.settings.LlmPreferences
) {
    enum class MigrationResult {
        SUCCESS,
        ALREADY_MIGRATED,
        NO_CUSTOM_KEY,
        ENCRYPTION_UNAVAILABLE,
        FAILED
    }
    
    suspend fun migrate(): MigrationResult {
        return try {
            // Check if encryption is available
            if (!llmPreferences.isSecureStorageAvailable()) {
                Log.w(TAG, "Secure storage unavailable, skipping migration")
                return MigrationResult.ENCRYPTION_UNAVAILABLE
            }
            
            // Already migrated?
            if (llmPreferences.hasApiKey()) {
                Log.d(TAG, "API key already in secure storage")
                return MigrationResult.ALREADY_MIGRATED
            }
            
            // Get old key
            val oldKey = preferences.readLegacyGeminiApiKeyForMigration()
            
            if (KnownGeminiPlaceholderKeys.isPlaceholder(oldKey)) {
                Log.d(TAG, "No custom API key to migrate")
                return MigrationResult.NO_CUSTOM_KEY
            }
            
            // Migrate to secure storage
            llmPreferences.setApiKey(oldKey)
            
            // Clear from old storage
            preferences.clearGeminiApiKey()
            
            Log.i(TAG, "API key migrated to secure storage")
            MigrationResult.SUCCESS
            
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Migration failed", e)
            MigrationResult.FAILED
        }
    }
    
    companion object {
        private const val TAG = "ApiKeyMigration"
    }
}
