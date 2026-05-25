package dev.chirpboard.app.core.audio

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
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
    fun `recording quality preset exposes backend configs`() {
        assertEquals(64_000, RecordingQualityPreset.Low.appRecordingConfig.bitRate)
        assertEquals(24_000, RecordingQualityPreset.Low.appRecordingConfig.sampleRate)
        assertEquals(32_000, RecordingQualityPreset.Low.keyboardRecordingConfig.bitRate)
        assertEquals(128_000, RecordingQualityPreset.High.appRecordingConfig.bitRate)
        assertEquals(44_100, RecordingQualityPreset.High.appRecordingConfig.sampleRate)
        assertEquals(96_000, RecordingQualityPreset.High.keyboardRecordingConfig.bitRate)
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

    override fun readLegacyAppMicrophoneGain(): Float? = appMicrophoneGain
}
