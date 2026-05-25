package dev.chirpboard.app.core.audio

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.chirpboard.app.core.di.AudioSettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton

const val SAVED_RECORDING_FORMAT_LABEL = "M4A (AAC)"
const val DEFAULT_MICROPHONE_GAIN = 1.0f
const val MIN_MICROPHONE_GAIN = 1.0f
const val MAX_MICROPHONE_GAIN = 5.0f

data class AppRecordingQualityConfig(
    val bitRate: Int,
    val sampleRate: Int,
)

data class KeyboardRecordingQualityConfig(
    val bitRate: Int,
)

enum class RecordingQualityPreset(
    val storageValue: String,
    val appRecordingConfig: AppRecordingQualityConfig,
    val keyboardRecordingConfig: KeyboardRecordingQualityConfig,
) {
    Low(
        storageValue = "low",
        appRecordingConfig = AppRecordingQualityConfig(bitRate = 64_000, sampleRate = 24_000),
        keyboardRecordingConfig = KeyboardRecordingQualityConfig(bitRate = 32_000),
    ),
    Balanced(
        storageValue = "balanced",
        appRecordingConfig = AppRecordingQualityConfig(bitRate = 96_000, sampleRate = 32_000),
        keyboardRecordingConfig = KeyboardRecordingQualityConfig(bitRate = 64_000),
    ),
    High(
        storageValue = "high",
        appRecordingConfig = AppRecordingQualityConfig(bitRate = 128_000, sampleRate = 44_100),
        keyboardRecordingConfig = KeyboardRecordingQualityConfig(bitRate = 96_000),
    ),
    ;

    companion object {
        val DEFAULT = High

        fun fromStorageValue(value: String?): RecordingQualityPreset = entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}

data class AudioSettings(
    val microphoneGain: Float = DEFAULT_MICROPHONE_GAIN,
    val recordingQualityPreset: RecordingQualityPreset = RecordingQualityPreset.DEFAULT,
    val savedFormatLabel: String = SAVED_RECORDING_FORMAT_LABEL,
)

interface AudioSettingsMigrationSource {
    suspend fun readLegacyKeyboardMicrophoneGain(): Float?

    fun readLegacyAppMicrophoneGain(): Float?
}

@Singleton
class AudioSettingsStore
    @Inject
    constructor(
        @AudioSettingsDataStore private val dataStore: DataStore<Preferences>,
        private val migrationSource: AudioSettingsMigrationSource,
    ) {
        private object Keys {
            val microphoneGain = floatPreferencesKey("microphone_gain")
            val recordingQualityPreset = stringPreferencesKey("recording_quality_preset")
            val migrationComplete = booleanPreferencesKey("audio_settings_migration_complete")
        }

        private val migrationMutex = Mutex()

        val settings: Flow<AudioSettings> =
            dataFlow { preferences ->
                preferences.toAudioSettings()
            }

        val microphoneGain: Flow<Float> = settings.map { it.microphoneGain }

        val recordingQualityPreset: Flow<RecordingQualityPreset> = settings.map { it.recordingQualityPreset }

        suspend fun setMicrophoneGain(gain: Float) {
            ensureMigrated()
            dataStore.edit { preferences ->
                preferences[Keys.microphoneGain] = gain.coerceIn(MIN_MICROPHONE_GAIN, MAX_MICROPHONE_GAIN)
            }
        }

        suspend fun setRecordingQualityPreset(preset: RecordingQualityPreset) {
            ensureMigrated()
            dataStore.edit { preferences ->
                preferences[Keys.recordingQualityPreset] = preset.storageValue
            }
        }

        suspend fun currentSettings(): AudioSettings {
            ensureMigrated()
            return dataStore.data.first().toAudioSettings()
        }

        suspend fun currentMicrophoneGain(): Float = currentSettings().microphoneGain

        suspend fun currentRecordingQualityPreset(): RecordingQualityPreset = currentSettings().recordingQualityPreset

        private fun dataFlow(transform: (Preferences) -> AudioSettings): Flow<AudioSettings> =
            flow {
                ensureMigrated()
                emitAll(dataStore.data.map(transform))
            }

        private suspend fun ensureMigrated() {
            migrationMutex.withLock {
                val currentPreferences = dataStore.data.first()
                if (currentPreferences[Keys.migrationComplete] == true) {
                    return
                }

                val migratedMicrophoneGain =
                    currentPreferences[Keys.microphoneGain]?.coerceIn(MIN_MICROPHONE_GAIN, MAX_MICROPHONE_GAIN)
                        ?: migrationSource.readLegacyKeyboardMicrophoneGain()?.coerceIn(MIN_MICROPHONE_GAIN, MAX_MICROPHONE_GAIN)
                        ?: migrationSource.readLegacyAppMicrophoneGain()?.coerceIn(MIN_MICROPHONE_GAIN, MAX_MICROPHONE_GAIN)
                        ?: DEFAULT_MICROPHONE_GAIN

                val normalizedPreset =
                    RecordingQualityPreset.fromStorageValue(currentPreferences[Keys.recordingQualityPreset])

                dataStore.edit { preferences ->
                    if (preferences[Keys.microphoneGain] == null) {
                        preferences[Keys.microphoneGain] = migratedMicrophoneGain
                    }
                    preferences[Keys.recordingQualityPreset] = normalizedPreset.storageValue
                    preferences[Keys.migrationComplete] = true
                }
            }
        }

        private fun Preferences.toAudioSettings(): AudioSettings =
            AudioSettings(
                microphoneGain = readMicrophoneGain(),
                recordingQualityPreset = readRecordingQualityPreset(),
            )

        private fun Preferences.readMicrophoneGain(): Float =
            this[Keys.microphoneGain]?.coerceIn(MIN_MICROPHONE_GAIN, MAX_MICROPHONE_GAIN) ?: DEFAULT_MICROPHONE_GAIN

        private fun Preferences.readRecordingQualityPreset(): RecordingQualityPreset =
            RecordingQualityPreset.fromStorageValue(this[Keys.recordingQualityPreset])

    }
