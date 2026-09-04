package dev.chirpboard.app.core.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.chirpboard.app.core.audio.AudioSettingsMigrationSource
import dev.chirpboard.app.core.audio.AudioSettingsStore
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardPreferencesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val testScope = TestScope(UnconfinedTestDispatcher())

    @Test
    fun `floating bubble starts on the upper left`() =
        testScope.runTest {
            val preferences = createPreferences("default.preferences_pb")

            val position = preferences.floatingBubblePosition.first()

            assertFalse(position.onRight)
            assertEquals(0.35f, position.yFraction)
        }

    @Test
    fun `floating bubble position survives a new preferences instance`() =
        testScope.runTest {
            val dataStore =
                PreferenceDataStoreFactory.create(
                    scope = testScope,
                    produceFile = { File(temporaryFolder.root, "saved.preferences_pb") },
                )
            val movedPosition = FloatingBubblePosition(onRight = true, yFraction = 0.72f)
            val audioSettings = createAudioSettings("saved_audio.preferences_pb")

            KeyboardPreferences(dataStore, audioSettings).setFloatingBubblePosition(movedPosition)

            assertEquals(
                movedPosition,
                KeyboardPreferences(dataStore, audioSettings)
                    .floatingBubblePosition
                    .first(),
            )
        }

    private fun createPreferences(fileName: String): KeyboardPreferences {
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = testScope,
                produceFile = { File(temporaryFolder.root, fileName) },
            )
        return KeyboardPreferences(dataStore, createAudioSettings("audio_$fileName"))
    }

    private fun createAudioSettings(fileName: String): AudioSettingsStore =
        AudioSettingsStore(
            dataStore =
                PreferenceDataStoreFactory.create(
                    scope = testScope,
                    produceFile = { File(temporaryFolder.root, fileName) },
                ),
            migrationSource = EmptyAudioSettingsMigrationSource,
        )
}

private object EmptyAudioSettingsMigrationSource : AudioSettingsMigrationSource {
    override suspend fun readLegacyKeyboardMicrophoneGain(): Float? = null

    override suspend fun readLegacyAppMicrophoneGain(): Float? = null
}
