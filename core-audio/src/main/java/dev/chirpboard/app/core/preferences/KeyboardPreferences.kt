package dev.chirpboard.app.core.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.chirpboard.app.core.audio.AudioSettingsStore
import dev.chirpboard.app.core.audio.RecordingOutputFormat
import dev.chirpboard.app.core.audio.RecordingQualityPreset
import dev.chirpboard.app.core.di.KeyboardPreferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

const val DEFAULT_QUICK_INPUT_NOTIFICATION_TIMEOUT_MS = 30_000L
val QUICK_INPUT_NOTIFICATION_TIMEOUT_OPTIONS_MS = listOf(30_000L, 60_000L, 300_000L)

const val DEFAULT_FLOATING_BUBBLE_Y_FRACTION = 0.55f

/** Keeps a persisted bubble anchor away from the status bar and the keyboard area. */
val FLOATING_BUBBLE_Y_FRACTION_RANGE = 0.05f..0.85f

/**
 * BUB-1: where the floating mic bubble sits — snapped to the left or right screen edge,
 * with a vertical anchor stored as a fraction of the screen height so the position
 * survives rotation and display-size changes.
 */
data class FloatingBubblePosition(
    val onRight: Boolean = true,
    val yFraction: Float = DEFAULT_FLOATING_BUBBLE_Y_FRACTION,
)

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
        val quickInputNotificationTimeoutMs = longPreferencesKey("quick_input_notification_timeout_ms")
        val dictationHistoryEnabled = booleanPreferencesKey("dictation_history_enabled")
        val floatingMicBubbleEnabled = booleanPreferencesKey("floating_mic_bubble_enabled")
        val floatingBubbleOnRight = booleanPreferencesKey("floating_bubble_on_right")
        val floatingBubbleYFraction = floatPreferencesKey("floating_bubble_y_fraction")
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
     * Whether LLM post-processing is enabled for keyboard transcriptions. PLH-8: the
     * system voice-recognition dialog shares this key too, so both quick-dictation
     * surfaces follow the same toggle.
     */
    val llmEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.llmEnabled] ?: true
    }

    /**
     * HIST-1: whether successfully delivered quick dictations (keyboard + voice dialog)
     * are kept as capped text-only history entries. On by default: delivery to another
     * app is not durable (some editors drop the committed text), and with
     * [saveKeyboardRecordings] off this history is the only recovery path once the
     * quick-input notification times out. Incognito and secure sessions are excluded
     * upstream and never write history regardless of this toggle.
     */
    val dictationHistoryEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.dictationHistoryEnabled] ?: true
    }

    /**
     * BUB-1: whether the draggable floating mic bubble shows while the Chirp keyboard is
     * open. Off by default — it draws over the host app and needs the separate
     * "display over other apps" grant, so it is strictly opt-in.
     */
    val floatingMicBubbleEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.floatingMicBubbleEnabled] ?: false
    }

    /** Persisted bubble anchor; the fraction is re-coerced on read in case an old value drifted. */
    val floatingBubblePosition: Flow<FloatingBubblePosition> = dataStore.data.map { preferences ->
        FloatingBubblePosition(
            onRight = preferences[Keys.floatingBubbleOnRight] ?: true,
            yFraction =
                (preferences[Keys.floatingBubbleYFraction] ?: DEFAULT_FLOATING_BUBBLE_Y_FRACTION)
                    .coerceIn(FLOATING_BUBBLE_Y_FRACTION_RANGE),
        )
    }

    /** How long a completed quick-input result stays available for one-tap copying. */
    val quickInputNotificationTimeoutMs: Flow<Long> = dataStore.data.map { preferences ->
        preferences[Keys.quickInputNotificationTimeoutMs]
            ?.takeIf { it in QUICK_INPUT_NOTIFICATION_TIMEOUT_OPTIONS_MS }
            ?: DEFAULT_QUICK_INPUT_NOTIFICATION_TIMEOUT_MS
    }

    /**
     * Shared microphone gain multiplier (1.0 = no boost, up to 5.0 = 5x boost).
     */
    val microphoneGain: Flow<Float> = audioSettingsStore.microphoneGain

    /**
     * Shared recording quality preset for saved recordings.
     */
    val recordingQualityPreset: Flow<RecordingQualityPreset> = audioSettingsStore.recordingQualityPreset

    /**
     * Shared output format for saved recordings.
     */
    val outputFormat: Flow<RecordingOutputFormat> = audioSettingsStore.outputFormat

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

    suspend fun setDictationHistoryEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.dictationHistoryEnabled] = enabled
        }
    }

    suspend fun setFloatingMicBubbleEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.floatingMicBubbleEnabled] = enabled
        }
    }

    suspend fun setFloatingBubblePosition(position: FloatingBubblePosition) {
        dataStore.edit { preferences ->
            preferences[Keys.floatingBubbleOnRight] = position.onRight
            preferences[Keys.floatingBubbleYFraction] =
                position.yFraction.coerceIn(FLOATING_BUBBLE_Y_FRACTION_RANGE)
        }
    }

    suspend fun setQuickInputNotificationTimeoutMs(timeoutMs: Long) {
        require(timeoutMs in QUICK_INPUT_NOTIFICATION_TIMEOUT_OPTIONS_MS)
        dataStore.edit { preferences ->
            preferences[Keys.quickInputNotificationTimeoutMs] = timeoutMs
        }
    }

    suspend fun setMicrophoneGain(gain: Float) {
        audioSettingsStore.setMicrophoneGain(gain)
    }

    suspend fun setRecordingQualityPreset(preset: RecordingQualityPreset) {
        audioSettingsStore.setRecordingQualityPreset(preset)
    }

    suspend fun setOutputFormat(format: RecordingOutputFormat) {
        audioSettingsStore.setOutputFormat(format)
    }
}
