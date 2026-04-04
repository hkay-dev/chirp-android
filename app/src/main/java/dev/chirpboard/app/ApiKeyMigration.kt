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
    private val securePreferences: SecurePreferences
) {
    enum class MigrationResult {
        SUCCESS,
        ALREADY_MIGRATED,
        NO_CUSTOM_KEY,
        ENCRYPTION_UNAVAILABLE,
        FAILED
    }
    
    fun migrate(): MigrationResult {
        return try {
            // Check if encryption is available
            if (!securePreferences.isAvailable()) {
                Log.w(TAG, "Secure storage unavailable, skipping migration")
                return MigrationResult.ENCRYPTION_UNAVAILABLE
            }
            
            // Already migrated?
            if (securePreferences.hasApiKey()) {
                Log.d(TAG, "API key already in secure storage")
                return MigrationResult.ALREADY_MIGRATED
            }
            
            // Get old key
            val oldKey = preferences.geminiApiKey
            
            // Skip if it's the default/empty key
            if (oldKey.isBlank() || oldKey == OLD_DEFAULT_KEY || oldKey == CURRENT_DEFAULT_KEY) {
                Log.d(TAG, "No custom API key to migrate")
                return MigrationResult.NO_CUSTOM_KEY
            }
            
            // Migrate to secure storage
            securePreferences.geminiApiKey = oldKey
            
            // Clear from old storage
            preferences.clearGeminiApiKey()
            
            Log.i(TAG, "API key migrated to secure storage")
            MigrationResult.SUCCESS
            
        } catch (e: Exception) {
            Log.e(TAG, "Migration failed", e)
            MigrationResult.FAILED
        }
    }
    
    companion object {
        private const val TAG = "ApiKeyMigration"
        private const val OLD_DEFAULT_KEY = "[removed-google-api-key]"
        private const val CURRENT_DEFAULT_KEY = "[removed-google-api-key]"
    }
}
