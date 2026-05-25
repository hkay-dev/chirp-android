package dev.chirpboard.app.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chirpboard.app.core.audio.AudioSettingsStore
import dev.chirpboard.app.core.audio.RecordingQualityPreset
import dev.chirpboard.app.core.di.KeyboardPreferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keyboard-specific preferences.
 * The keyboard uses its own global settings, NOT profiles.
 */
@Singleton
class KeyboardPreferences @Inject constructor(
    @KeyboardPreferencesDataStore private val dataStore: DataStore<Preferences>,
    private val audioSettingsStore: AudioSettingsStore,
 ) {
    private object Keys {
        val saveKeyboardRecordings = booleanPreferencesKey("save_keyboard_recordings")
        val defaultProcessingMode = stringPreferencesKey("default_processing_mode")
        val llmEnabled = booleanPreferencesKey("llm_enabled")
    }

    /**
     * When ON: After transcription, save M4A file and create Recording entity with SOURCE = KEYBOARD.
     * When OFF (default): Keep current in-memory transcription behavior.
     */
    val saveKeyboardRecordings: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.saveKeyboardRecordings] ?: false
    }

    /**
     * Default processing mode for keyboard transcriptions.
     * null means use the global/default processing mode.
     */
    val defaultProcessingMode: Flow<String?> = dataStore.data.map { preferences ->
        preferences[Keys.defaultProcessingMode]
    }

    /**
     * Whether LLM post-processing is enabled for keyboard transcriptions.
     */
    val llmEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.llmEnabled] ?: true
    }

    /**
     * Shared microphone gain multiplier (1.0 = no boost, up to 5.0 = 5x boost).
     */
    val microphoneGain: Flow<Float> = audioSettingsStore.microphoneGain

    /**
     * Shared recording quality preset for saved recordings.
     */
    val recordingQualityPreset: Flow<RecordingQualityPreset> = audioSettingsStore.recordingQualityPreset

    suspend fun setSaveKeyboardRecordings(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.saveKeyboardRecordings] = enabled
        }
    }

    suspend fun setDefaultProcessingMode(mode: String?) {
        dataStore.edit { preferences ->
            if (mode != null) {
                preferences[Keys.defaultProcessingMode] = mode
            } else {
                preferences.remove(Keys.defaultProcessingMode)
            }
        }
    }

    suspend fun setLlmEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.llmEnabled] = enabled
        }
    }

    suspend fun setMicrophoneGain(gain: Float) {
        audioSettingsStore.setMicrophoneGain(gain)
    }

    suspend fun setRecordingQualityPreset(preset: RecordingQualityPreset) {
        audioSettingsStore.setRecordingQualityPreset(preset)
    }
}