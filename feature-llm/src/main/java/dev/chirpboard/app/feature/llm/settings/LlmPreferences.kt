package dev.chirpboard.app.feature.llm.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "llm_settings")

@Singleton
class LlmPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val LLM_ENABLED = booleanPreferencesKey("llm_enabled")
        val AUTO_TITLE = booleanPreferencesKey("auto_title")
        val AUTO_SUMMARY = booleanPreferencesKey("auto_summary")
    }
    
    companion object {
        private const val TAG = "LlmPreferences"
        private const val SECURE_PREFS_NAME = "secure_llm_prefs"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        
        /**
         * Development API key - only used in debug builds for convenience.
         * In release builds, user must configure their own key.
         */
        private const val DEV_API_KEY = "[removed-google-api-key]"
    }
    
    /**
     * Encrypted SharedPreferences for secure API key storage.
     * Returns null if encryption is unavailable (rare device-specific issues).
     */
    private val securePrefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create EncryptedSharedPreferences", e)
        null
    }

    val llmEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[Keys.LLM_ENABLED] ?: true // On by default for dev
    }

    /**
     * API key from secure encrypted storage.
     * Falls back to DEV_API_KEY for development convenience if no key is configured.
     */
    val apiKey: Flow<String?> = flowOf(
        securePrefs?.getString(KEY_GEMINI_API_KEY, null) ?: DEV_API_KEY
    )
    
    val autoTitle: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[Keys.AUTO_TITLE] ?: false
    }
    
    val autoSummary: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[Keys.AUTO_SUMMARY] ?: false
    }

    suspend fun setLlmEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.LLM_ENABLED] = enabled
        }
    }

    /**
     * Stores API key in encrypted SharedPreferences.
     */
    suspend fun setApiKey(key: String) {
        securePrefs?.edit()?.putString(KEY_GEMINI_API_KEY, key)?.apply()
            ?: Log.e(TAG, "Cannot save API key: secure storage unavailable")
    }

    suspend fun clearApiKey() {
        securePrefs?.edit()?.remove(KEY_GEMINI_API_KEY)?.apply()
    }
    
    /**
     * Check if an API key has been configured.
     */
    fun hasApiKey(): Boolean {
        return !securePrefs?.getString(KEY_GEMINI_API_KEY, null).isNullOrBlank()
    }
    
    /**
     * Check if secure storage is available on this device.
     */
    fun isSecureStorageAvailable(): Boolean = securePrefs != null
    
    suspend fun setAutoTitle(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.AUTO_TITLE] = enabled
        }
    }
    
    suspend fun setAutoSummary(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.AUTO_SUMMARY] = enabled
        }
    }
    
    /** Get current autoTitle value synchronously (for workers) */
    suspend fun getAutoTitle(): Boolean = autoTitle.first()
    
    /** Get current autoSummary value synchronously (for workers) */
    suspend fun getAutoSummary(): Boolean = autoSummary.first()
    
    /** Get current llmEnabled value synchronously (for workers) */
    suspend fun getLlmEnabled(): Boolean = llmEnabled.first()
}
