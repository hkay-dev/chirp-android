package dev.parakeeboard.app.feature.llm.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
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
    }

    val apiKey: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[Keys.API_KEY]
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
}
