package dev.chirpboard.app.core.audio

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AudioSettingsStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun `microphone gain migrates from keyboard settings before legacy app prefs`() =
        testScope.runTest {
            val dataStore = createDataStore("audio_settings.preferences_pb")
            val store =
                AudioSettingsStore(
                    dataStore = dataStore,
                    migrationSource =
                        FakeAudioSettingsMigrationSource(
                            keyboardMicrophoneGain = 2.4f,
                            appMicrophoneGain = 1.6f,
                        ),
                )

            store.microphoneGain.test {
                assertEquals(2.4f, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `microphone gain falls back to legacy app prefs when keyboard value is missing`() =
        testScope.runTest {
            val dataStore = createDataStore("audio_settings_fallback.preferences_pb")
            val store =
                AudioSettingsStore(
                    dataStore = dataStore,
                    migrationSource =
                        FakeAudioSettingsMigrationSource(
                            keyboardMicrophoneGain = null,
                            appMicrophoneGain = 1.8f,
                        ),
                )

            assertEquals(1.8f, store.currentMicrophoneGain())
        }

    @Test
    fun `invalid recording quality falls back to default preset`() =
        testScope.runTest {
            val dataStore = createDataStore("audio_settings_invalid.preferences_pb")
            dataStore.edit { preferences ->
                preferences[stringPreferencesKey("recording_quality_preset")] = "broken"
            }
            val store =
                AudioSettingsStore(
                    dataStore = dataStore,
                    migrationSource = FakeAudioSettingsMigrationSource(),
                )

            assertEquals(RecordingQualityPreset.DEFAULT, store.currentRecordingQualityPreset())
            assertEquals(
                RecordingQualityPreset.DEFAULT.storageValue,
                dataStore.data.first()[stringPreferencesKey("recording_quality_preset")],
            )
        }

    @Test
    fun `output format defaults to m4a and can be updated`() =
        testScope.runTest {
            val dataStore = createDataStore("audio_settings_format.preferences_pb")
            val store =
                AudioSettingsStore(
                    dataStore = dataStore,
                    migrationSource = FakeAudioSettingsMigrationSource(),
                )

            assertEquals(RecordingOutputFormat.M4A, store.currentOutputFormat())
            store.setOutputFormat(RecordingOutputFormat.WAV)
            assertEquals(RecordingOutputFormat.WAV, store.currentOutputFormat())
        }

    @Test
    fun `legacy DataStore without outputFormat persists default on first read`() =
        testScope.runTest {
            val dataStore = createDataStore("audio_settings_legacy_format.preferences_pb")
            dataStore.edit { preferences ->
                preferences[floatPreferencesKey("microphone_gain")] = 1.0f
                preferences[stringPreferencesKey("recording_quality_preset")] = RecordingQualityPreset.High.storageValue
                preferences[booleanPreferencesKey("audio_settings_migration_complete")] = true
            }
            val store =
                AudioSettingsStore(
                    dataStore = dataStore,
                    migrationSource = FakeAudioSettingsMigrationSource(),
                )

            assertEquals(RecordingOutputFormat.M4A, store.currentOutputFormat())
            assertEquals(
                RecordingOutputFormat.M4A.storageValue,
                dataStore.data.first()[stringPreferencesKey("output_format")],
            )
        }

    @Test
    fun `recording quality preset exposes backend configs`() {
        assertEquals(64_000, RecordingQualityPreset.Low.appRecordingConfig.bitRate)
        assertEquals(24_000, RecordingQualityPreset.Low.appRecordingConfig.sampleRate)
        assertEquals(32_000, RecordingQualityPreset.Low.keyboardRecordingConfig.bitRate)
        assertEquals(128_000, RecordingQualityPreset.High.appRecordingConfig.bitRate)
        assertEquals(44_100, RecordingQualityPreset.High.appRecordingConfig.sampleRate)
        assertEquals(96_000, RecordingQualityPreset.High.keyboardRecordingConfig.bitRate)
    }

    @Test
    fun `playback speed defaults to 1x and persists snapped values`() =
        testScope.runTest {
            val dataStore = createDataStore("audio_settings_speed.preferences_pb")
            val store =
                AudioSettingsStore(
                    dataStore = dataStore,
                    migrationSource = FakeAudioSettingsMigrationSource(),
                )

            assertEquals(1.0f, store.currentPlaybackSpeed())

            store.setPlaybackSpeed(1.3f)
            assertEquals(1.25f, store.currentPlaybackSpeed())

            store.setPlaybackSpeed(2.0f)
            assertEquals(2.0f, store.currentPlaybackSpeed())
        }

    @Test
    fun `nearestPlaybackSpeed snaps arbitrary values to supported options`() {
        assertEquals(0.75f, AudioSettingsStore.nearestPlaybackSpeed(0.1f))
        assertEquals(1.0f, AudioSettingsStore.nearestPlaybackSpeed(1.05f))
        assertEquals(1.5f, AudioSettingsStore.nearestPlaybackSpeed(1.6f))
        assertEquals(2.0f, AudioSettingsStore.nearestPlaybackSpeed(99f))
    }

    // The audio-settings DataStore is included in Auto Backup (data_extraction_rules.xml),
    // so values written by other devices or app versions arrive unvalidated; every read
    // must degrade to a safe in-range value, never crash or feed garbage into capture.

    @Test
    fun `restored out-of-range microphone gain is coerced into the supported range on read`() =
        testScope.runTest {
            val dataStore = createDataStore("audio_settings_restored_gain.preferences_pb")
            dataStore.edit { preferences ->
                preferences[floatPreferencesKey("microphone_gain")] = 99f
                preferences[booleanPreferencesKey("audio_settings_migration_complete")] = true
            }
            val store =
                AudioSettingsStore(
                    dataStore = dataStore,
                    migrationSource = FakeAudioSettingsMigrationSource(),
                )

            assertEquals(MAX_MICROPHONE_GAIN, store.currentMicrophoneGain())
        }

    @Test
    fun `restored arbitrary playback speed snaps to a supported option on read`() =
        testScope.runTest {
            val dataStore = createDataStore("audio_settings_restored_speed.preferences_pb")
            dataStore.edit { preferences ->
                preferences[floatPreferencesKey("playback_speed")] = 3.7f
                preferences[booleanPreferencesKey("audio_settings_migration_complete")] = true
            }
            val store =
                AudioSettingsStore(
                    dataStore = dataStore,
                    migrationSource = FakeAudioSettingsMigrationSource(),
                )

            assertEquals(2.0f, store.currentPlaybackSpeed())
        }

    @Test
    fun `restored unknown output format and device policy fall back to defaults`() =
        testScope.runTest {
            val dataStore = createDataStore("audio_settings_restored_enums.preferences_pb")
            dataStore.edit { preferences ->
                preferences[stringPreferencesKey("output_format")] = "ogg"
                preferences[stringPreferencesKey("input_device_policy")] = "from-the-future"
                preferences[booleanPreferencesKey("audio_settings_migration_complete")] = true
            }
            val store =
                AudioSettingsStore(
                    dataStore = dataStore,
                    migrationSource = FakeAudioSettingsMigrationSource(),
                )

            val settings = store.currentSettings()
            assertEquals(RecordingOutputFormat.M4A, settings.outputFormat)
            assertEquals(AudioInputDevicePolicy.Automatic, settings.inputDevicePolicy)
        }

    @Test
    fun `manual device selection persists key and display name together`() =
        testScope.runTest {
            val dataStore = createDataStore("audio_settings_manual_device.preferences_pb")
            val store =
                AudioSettingsStore(
                    dataStore = dataStore,
                    migrationSource = FakeAudioSettingsMigrationSource(),
                )

            store.setManualDevice("device:26:Buds", displayName = "Buds")
            var settings = store.currentSettings()
            assertEquals("device:26:Buds", settings.manualDeviceAddress)
            assertEquals("Buds", settings.manualDeviceName)

            // Clearing the key clears the stored name too.
            store.setManualDevice(null, displayName = null)
            settings = store.currentSettings()
            assertEquals(null, settings.manualDeviceAddress)
            assertEquals(null, settings.manualDeviceName)
        }

    @Test
    fun `selectManualDevice writes key name and manual policy in a single emission`() =
        testScope.runTest {
            val dataStore = createDataStore("audio_settings_select_manual.preferences_pb")
            val store =
                AudioSettingsStore(
                    dataStore = dataStore,
                    migrationSource = FakeAudioSettingsMigrationSource(),
                )

            store.settings.test {
                // Drain the initial post-migration state before selecting a device.
                val initial = awaitItem()
                assertEquals(AudioInputDevicePolicy.Automatic, initial.inputDevicePolicy)
                assertEquals(null, initial.manualDeviceAddress)

                store.selectManualDevice("device:26:Buds", displayName = "Buds")

                // A single emission must carry the key, name AND the flipped policy together,
                // so a capture racing the selection never sees the new key under Automatic.
                val updated = awaitItem()
                assertEquals(AudioInputDevicePolicy.Manual, updated.inputDevicePolicy)
                assertEquals("device:26:Buds", updated.manualDeviceAddress)
                assertEquals("Buds", updated.manualDeviceName)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `selectAutomatic flips policy and leaves the stale manual key in place`() =
        testScope.runTest {
            val dataStore = createDataStore("audio_settings_select_automatic.preferences_pb")
            val store =
                AudioSettingsStore(
                    dataStore = dataStore,
                    migrationSource = FakeAudioSettingsMigrationSource(),
                )

            store.selectManualDevice("device:26:Buds", displayName = "Buds")
            store.selectAutomatic()

            val settings = store.currentSettings()
            assertEquals(AudioInputDevicePolicy.Automatic, settings.inputDevicePolicy)
            // The manual key/name are deliberately retained so switching back restores them.
            assertEquals("device:26:Buds", settings.manualDeviceAddress)
            assertEquals("Buds", settings.manualDeviceName)
        }

    @Test
    fun `setManualDeviceAddress keeps working as a name-less selection`() =
        testScope.runTest {
            val dataStore = createDataStore("audio_settings_manual_addr.preferences_pb")
            val store =
                AudioSettingsStore(
                    dataStore = dataStore,
                    migrationSource = FakeAudioSettingsMigrationSource(),
                )

            store.setManualDeviceAddress("card=1;device=0")
            val settings = store.currentSettings()
            assertEquals("card=1;device=0", settings.manualDeviceAddress)
            assertEquals(null, settings.manualDeviceName)
        }

    private fun createDataStore(fileName: String) =
        PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { File(temporaryFolder.root, fileName) },
        )
}

private class FakeAudioSettingsMigrationSource(
    private val keyboardMicrophoneGain: Float? = null,
    private val appMicrophoneGain: Float? = null,
) : AudioSettingsMigrationSource {
    override suspend fun readLegacyKeyboardMicrophoneGain(): Float? = keyboardMicrophoneGain

    override suspend fun readLegacyAppMicrophoneGain(): Float? = appMicrophoneGain
}
