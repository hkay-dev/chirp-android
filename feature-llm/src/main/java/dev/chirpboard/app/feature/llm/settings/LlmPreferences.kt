package dev.chirpboard.app.feature.llm.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "llm_settings")

@Singleton
class LlmPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val API_KEY = stringPreferencesKey("gemini_api_key")
        val AUTO_TITLE = booleanPreferencesKey("auto_title")
        val AUTO_SUMMARY = booleanPreferencesKey("auto_summary")
    }
    
    companion object {
        // DEV MODE ONLY - hardcoded API key for development convenience
        private const val DEV_API_KEY = "[removed-google-api-key]"
    }

    val apiKey: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[Keys.API_KEY] ?: DEV_API_KEY
    }
    
    val autoTitle: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[Keys.AUTO_TITLE] ?: false
    }
    
    val autoSummary: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[Keys.AUTO_SUMMARY] ?: false
    }

    suspend fun setApiKey(key: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.API_KEY] = key
        }
    }

    suspend fun clearApiKey() {
        context.dataStore.edit { preferences ->
            preferences.remove(Keys.API_KEY)
        }
    }
    
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
}
