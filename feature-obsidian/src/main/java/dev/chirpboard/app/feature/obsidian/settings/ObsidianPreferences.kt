package dev.chirpboard.app.feature.obsidian.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed preferences for Obsidian integration settings.
 */
@Singleton
class ObsidianPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private object Keys {
        val GLOBAL_VAULT_URI = stringPreferencesKey("global_vault_uri")
        val AUTO_EXPORT_ENABLED = booleanPreferencesKey("auto_export_enabled")
    }

    /**
     * Flow of the global vault URI string (SAF URI stored as string).
     * Convert to Uri using Uri.parse() when needed.
     */
    val globalVaultUri: Flow<String?> = dataStore.data.map { preferences ->
        preferences[Keys.GLOBAL_VAULT_URI]
    }

    /**
     * Flow of whether auto-export is enabled.
     * When enabled, recordings are automatically exported to Obsidian after transcription.
     */
    val autoExportEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.AUTO_EXPORT_ENABLED] ?: false
    }

    /**
     * Set the global vault URI.
     *
     * @param uri The SAF URI as a string, or null to clear
     */
    suspend fun setGlobalVaultUri(uri: String?) {
        dataStore.edit { preferences ->
            if (uri != null) {
                preferences[Keys.GLOBAL_VAULT_URI] = uri
            } else {
                preferences.remove(Keys.GLOBAL_VAULT_URI)
            }
        }
    }

    /**
     * Set whether auto-export is enabled.
     *
     * @param enabled true to enable auto-export
     */
    suspend fun setAutoExportEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.AUTO_EXPORT_ENABLED] = enabled
        }
    }

    /**
     * Clear all Obsidian settings.
     */
    suspend fun clearAll() {
        dataStore.edit { preferences ->
            preferences.clear()
        }
    }
}
