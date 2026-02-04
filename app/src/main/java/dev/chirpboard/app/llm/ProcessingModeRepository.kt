package dev.chirpboard.app.llm

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Extension property for DataStore singleton */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "processing_mode_preferences"
)

/**
 * Repository for persisting and observing the user's selected processing mode.
 * Uses Preferences DataStore for storage.
 */
class ProcessingModeRepository(private val context: Context) {

    companion object {
        private val KEY_MODE_ID = stringPreferencesKey("mode_id")
        private val KEY_CUSTOM_PROMPT = stringPreferencesKey("custom_prompt")

        private const val DEFAULT_MODE_ID = "raw"
    }

    /**
     * Flow of the current processing mode.
     * Emits whenever the mode or custom prompt changes.
     */
    val currentMode: Flow<ProcessingMode> = context.dataStore.data.map { preferences ->
        val modeId = preferences[KEY_MODE_ID] ?: DEFAULT_MODE_ID
        val customPrompt = preferences[KEY_CUSTOM_PROMPT]
        ProcessingMode.fromId(modeId, customPrompt)
    }

    /**
     * Flow of just the mode ID string.
     * Useful for UI that only needs to track which mode is selected.
     */
    val currentModeId: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_MODE_ID] ?: DEFAULT_MODE_ID
    }

    /**
     * Flow of the custom prompt text.
     * Returns empty string if no custom prompt is set.
     */
    val customPrompt: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_CUSTOM_PROMPT] ?: ""
    }

    /**
     * Update the selected processing mode.
     * @param mode The new processing mode to use
     */
    suspend fun setMode(mode: ProcessingMode) {
        context.dataStore.edit { preferences ->
            preferences[KEY_MODE_ID] = mode.id
            if (mode is ProcessingMode.Custom) {
                preferences[KEY_CUSTOM_PROMPT] = mode.customPrompt
            }
        }
    }

    /**
     * Update the selected mode by ID.
     * @param modeId The ID of the mode to select
     */
    suspend fun setModeById(modeId: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_MODE_ID] = modeId
        }
    }

    /**
     * Update the custom prompt text.
     * Also switches the mode to Custom if not already.
     * @param prompt The custom prompt text
     */
    suspend fun setCustomPrompt(prompt: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_CUSTOM_PROMPT] = prompt
            preferences[KEY_MODE_ID] = "custom"
        }
    }

    /**
     * Update only the custom prompt text without changing the current mode.
     * Useful for saving draft prompts while user is still on another mode.
     * @param prompt The custom prompt text to save
     */
    suspend fun saveCustomPromptDraft(prompt: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_CUSTOM_PROMPT] = prompt
        }
    }

    /**
     * Reset to default mode (Raw).
     */
    suspend fun reset() {
        context.dataStore.edit { preferences ->
            preferences[KEY_MODE_ID] = DEFAULT_MODE_ID
            preferences.remove(KEY_CUSTOM_PROMPT)
        }
    }
}
