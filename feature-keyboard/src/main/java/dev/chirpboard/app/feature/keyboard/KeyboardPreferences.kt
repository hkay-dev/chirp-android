package dev.chirpboard.app.feature.keyboard

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "keyboard_preferences")

/**
 * Keyboard-specific preferences.
 * The keyboard uses its own global settings, NOT profiles.
 */
@Singleton
class KeyboardPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val SAVE_KEYBOARD_RECORDINGS = booleanPreferencesKey("save_keyboard_recordings")
        val DEFAULT_PROCESSING_MODE = stringPreferencesKey("default_processing_mode")
        val LLM_ENABLED = booleanPreferencesKey("llm_enabled")
        val MICROPHONE_GAIN = floatPreferencesKey("microphone_gain")
    }

    /**
     * When ON: After transcription, save M4A file and create Recording entity with SOURCE = KEYBOARD.
     * When OFF (default): Keep current in-memory transcription behavior.
     */
    val saveKeyboardRecordings: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[Keys.SAVE_KEYBOARD_RECORDINGS] ?: false
    }

    /**
     * Default processing mode for keyboard transcriptions.
     * null means use the global/default processing mode.
     */
    val defaultProcessingMode: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[Keys.DEFAULT_PROCESSING_MODE]
    }

    /**
     * Whether LLM post-processing is enabled for keyboard transcriptions.
     */
    val llmEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[Keys.LLM_ENABLED] ?: true
    }

    /**
     * Microphone gain multiplier (1.0 = no boost, up to 5.0 = 5x boost).
     */
    val microphoneGain: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[Keys.MICROPHONE_GAIN] ?: 1.0f
    }

    suspend fun setSaveKeyboardRecordings(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.SAVE_KEYBOARD_RECORDINGS] = enabled
        }
    }

    suspend fun setDefaultProcessingMode(mode: String?) {
        context.dataStore.edit { preferences ->
            if (mode != null) {
                preferences[Keys.DEFAULT_PROCESSING_MODE] = mode
            } else {
                preferences.remove(Keys.DEFAULT_PROCESSING_MODE)
            }
        }
    }

    suspend fun setLlmEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.LLM_ENABLED] = enabled
        }
    }

    suspend fun setMicrophoneGain(gain: Float) {
        context.dataStore.edit { preferences ->
            preferences[Keys.MICROPHONE_GAIN] = gain.coerceIn(1.0f, 5.0f)
        }
    }
}
