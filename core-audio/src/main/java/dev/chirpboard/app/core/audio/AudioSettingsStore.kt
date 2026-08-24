package dev.chirpboard.app.core.audio

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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
const val DEFAULT_PLAYBACK_SPEED = 1.0f

/** Supported recording playback speeds, in cycle order. */
val PLAYBACK_SPEED_OPTIONS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

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
    val outputFormat: RecordingOutputFormat = RecordingOutputFormat.DEFAULT,
    val inputDevicePolicy: AudioInputDevicePolicy = AudioInputDevicePolicy.DEFAULT,
    val manualDeviceAddress: String? = null,
    /**
     * Display name persisted alongside [manualDeviceAddress] so a missing preferred
     * device can be named ("AirPods not connected") even while it is absent.
     */
    val manualDeviceName: String? = null,
    val batteryOptimizationPromptShown: Boolean = false,
    val playbackSpeed: Float = DEFAULT_PLAYBACK_SPEED,
) {
    val savedFormatLabel: String get() = outputFormat.displayLabel
}

interface AudioSettingsMigrationSource {
    suspend fun readLegacyKeyboardMicrophoneGain(): Float?

    /** Suspending because the only implementation reads SharedPreferences off the main thread. */
    suspend fun readLegacyAppMicrophoneGain(): Float?
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
            val outputFormat = stringPreferencesKey("output_format")
            val inputDevicePolicy = stringPreferencesKey("input_device_policy")
            val manualDeviceAddress = stringPreferencesKey("manual_device_address")
            val manualDeviceName = stringPreferencesKey("manual_device_name")
            val batteryOptimizationPromptShown = booleanPreferencesKey("battery_optimization_prompt_shown")
            val migrationComplete = booleanPreferencesKey("audio_settings_migration_complete")
            val playbackSpeed = floatPreferencesKey("playback_speed")
        }

        private val migrationMutex = Mutex()

        /** True once migration has been verified complete for this process. */
        @Volatile
        private var migrationVerified = false

        val settings: Flow<AudioSettings> =
            dataFlow { preferences ->
                preferences.toAudioSettings()
            }

        val microphoneGain: Flow<Float> = settings.map { it.microphoneGain }

        val recordingQualityPreset: Flow<RecordingQualityPreset> = settings.map { it.recordingQualityPreset }

        val outputFormat: Flow<RecordingOutputFormat> = settings.map { it.outputFormat }

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

        suspend fun setOutputFormat(format: RecordingOutputFormat) {
            ensureMigrated()
            dataStore.edit { preferences ->
                preferences[Keys.outputFormat] = format.storageValue
            }
        }

        suspend fun setInputDevicePolicy(policy: AudioInputDevicePolicy) {
            ensureMigrated()
            dataStore.edit { preferences ->
                preferences[Keys.inputDevicePolicy] = policy.storageValue
            }
        }

        suspend fun setManualDeviceAddress(address: String?) = setManualDevice(address, displayName = null)

        /**
         * Persists a manual device selection (stable selection key + display name) so the
         * preference survives the device disconnecting and the picker can name it while
         * absent. A blank key clears the selection.
         */
        suspend fun setManualDevice(
            selectionKey: String?,
            displayName: String?,
        ) {
            ensureMigrated()
            dataStore.edit { preferences ->
                if (selectionKey.isNullOrBlank()) {
                    preferences.remove(Keys.manualDeviceAddress)
                    preferences.remove(Keys.manualDeviceName)
                } else {
                    preferences[Keys.manualDeviceAddress] = selectionKey
                    if (displayName.isNullOrBlank()) {
                        preferences.remove(Keys.manualDeviceName)
                    } else {
                        preferences[Keys.manualDeviceName] = displayName
                    }
                }
            }
        }

        /**
         * Atomically selects a manual input device: writes the stable selection key, its
         * display name, and flips [Keys.inputDevicePolicy] to [AudioInputDevicePolicy.Manual]
         * in a single [DataStore.edit]. This avoids the two-edit window where a capture
         * starting between separate key and policy writes would read the new key under the
         * old (automatic) policy, and collapses the picker to a single `settings` emission.
         */
        suspend fun selectManualDevice(
            selectionKey: String,
            displayName: String?,
        ) {
            ensureMigrated()
            dataStore.edit { preferences ->
                preferences[Keys.manualDeviceAddress] = selectionKey
                if (displayName.isNullOrBlank()) {
                    preferences.remove(Keys.manualDeviceName)
                } else {
                    preferences[Keys.manualDeviceName] = displayName
                }
                preferences[Keys.inputDevicePolicy] = AudioInputDevicePolicy.Manual.storageValue
            }
        }

        /**
         * Atomically flips the policy to [AudioInputDevicePolicy.Automatic] in a single edit.
         * The stale manual key/name are deliberately left in place — the `manualDevice` getter
         * only consults them under [AudioInputDevicePolicy.Manual], so they stay dormant and
         * the prior selection is restored verbatim if the user switches back to Manual.
         */
        suspend fun selectAutomatic() {
            ensureMigrated()
            dataStore.edit { preferences ->
                preferences[Keys.inputDevicePolicy] = AudioInputDevicePolicy.Automatic.storageValue
            }
        }

        suspend fun setPlaybackSpeed(speed: Float) {
            ensureMigrated()
            dataStore.edit { preferences ->
                preferences[Keys.playbackSpeed] = nearestPlaybackSpeed(speed)
            }
        }

        suspend fun markBatteryOptimizationPromptShown() {
            ensureMigrated()
            dataStore.edit { preferences ->
                preferences[Keys.batteryOptimizationPromptShown] = true
            }
        }

        suspend fun currentSettings(): AudioSettings {
            ensureMigrated()
            return dataStore.data.first().toAudioSettings()
        }

        suspend fun currentMicrophoneGain(): Float = currentSettings().microphoneGain

        suspend fun currentRecordingQualityPreset(): RecordingQualityPreset = currentSettings().recordingQualityPreset

        suspend fun currentOutputFormat(): RecordingOutputFormat = currentSettings().outputFormat

        suspend fun currentPlaybackSpeed(): Float = currentSettings().playbackSpeed

        /**
         * DataStore surfaces read failures (a transient IOException on the preferences file) as
         * flow errors that would otherwise cancel every collector and leave settings screens
         * dead until process restart. Falling back to defaults keeps the UI usable.
         */
        private val preferences: Flow<Preferences> =
            dataStore.data.catch { error ->
                if (error is IOException) {
                    Log.e(TAG, "Could not read audio settings; falling back to defaults", error)
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }

        private fun dataFlow(transform: (Preferences) -> AudioSettings): Flow<AudioSettings> =
            flow {
                ensureMigrated()
                emitAll(preferences.map(transform))
            }

        private suspend fun ensureMigrated() {
            // Migration completes at most once per install; after the first verified pass
            // every getter/setter (including the capture-start path) skips the mutex and
            // DataStore read entirely.
            if (migrationVerified) return
            migrationMutex.withLock {
                if (migrationVerified) return
                val currentPreferences = dataStore.data.first()
                if (currentPreferences[Keys.migrationComplete] == true) {
                    if (currentPreferences[Keys.outputFormat] == null) {
                        dataStore.edit { preferences ->
                            preferences[Keys.outputFormat] = RecordingOutputFormat.DEFAULT.storageValue
                        }
                    }
                    migrationVerified = true
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
                    if (preferences[Keys.outputFormat] == null) {
                        preferences[Keys.outputFormat] = RecordingOutputFormat.DEFAULT.storageValue
                    }
                    preferences[Keys.migrationComplete] = true
                }
                migrationVerified = true
            }
        }

        private fun Preferences.toAudioSettings(): AudioSettings =
            AudioSettings(
                microphoneGain = readMicrophoneGain(),
                recordingQualityPreset = readRecordingQualityPreset(),
                outputFormat = readOutputFormat(),
                inputDevicePolicy = AudioInputDevicePolicy.fromStorageValue(this[Keys.inputDevicePolicy]),
                manualDeviceAddress = this[Keys.manualDeviceAddress],
                manualDeviceName = this[Keys.manualDeviceName],
                batteryOptimizationPromptShown = this[Keys.batteryOptimizationPromptShown] == true,
                playbackSpeed = this[Keys.playbackSpeed]?.let(::nearestPlaybackSpeed) ?: DEFAULT_PLAYBACK_SPEED,
            )

        private fun Preferences.readMicrophoneGain(): Float =
            this[Keys.microphoneGain]?.coerceIn(MIN_MICROPHONE_GAIN, MAX_MICROPHONE_GAIN) ?: DEFAULT_MICROPHONE_GAIN

        private fun Preferences.readRecordingQualityPreset(): RecordingQualityPreset =
            RecordingQualityPreset.fromStorageValue(this[Keys.recordingQualityPreset])

        private fun Preferences.readOutputFormat(): RecordingOutputFormat =
            RecordingOutputFormat.fromStorageValue(this[Keys.outputFormat])

        companion object {
            private const val TAG = "AudioSettingsStore"

            /** Snaps an arbitrary stored/requested value to the closest supported speed. */
            fun nearestPlaybackSpeed(speed: Float): Float =
                PLAYBACK_SPEED_OPTIONS.minByOrNull { option -> kotlin.math.abs(option - speed) }
                    ?: DEFAULT_PLAYBACK_SPEED
        }
    }
