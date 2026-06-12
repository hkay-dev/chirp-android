package dev.chirpboard.app.backup

import dev.chirpboard.app.core.audio.AudioSettings
import dev.chirpboard.app.core.audio.AudioSettingsStore
import dev.chirpboard.app.core.audio.RecordingOutputFormat
import dev.chirpboard.app.core.audio.RecordingQualityPreset
import dev.chirpboard.app.core.preferences.KeyboardPreferences
import dev.chirpboard.app.core.ui.theme.DynamicColorPreference
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsBackupDelegateTest {
    private val dynamicColor = mockk<DynamicColorPreference>(relaxed = true)
    private val keyboardPreferences = mockk<KeyboardPreferences>(relaxed = true)
    private val audioSettingsStore = mockk<AudioSettingsStore>(relaxed = true)
    private val llmPreferences = mockk<LlmPreferences>(relaxed = true)

    private fun delegate(): SettingsBackupDelegate =
        SettingsBackupDelegate(
            dynamicColorPreference = dynamicColor,
            keyboardPreferences = keyboardPreferences,
            audioSettingsStore = audioSettingsStore,
            llmPreferences = llmPreferences,
        )

    @Test
    fun `snapshot captures every covered preference`() =
        runTest {
            every { dynamicColor.useDynamicColor } returns flowOf(true)
            coEvery { llmPreferences.getLlmEnabled() } returns false
            coEvery { llmPreferences.getAutoTitle() } returns true
            coEvery { llmPreferences.getAutoSummary() } returns false
            every { keyboardPreferences.saveKeyboardRecordings } returns flowOf(true)
            every { keyboardPreferences.llmEnabled } returns flowOf(false)
            every { keyboardPreferences.defaultProcessingMode } returns flowOf(null)
            coEvery { audioSettingsStore.currentSettings() } returns
                AudioSettings(
                    microphoneGain = 2.5f,
                    recordingQualityPreset = RecordingQualityPreset.Balanced,
                    outputFormat = RecordingOutputFormat.MP3,
                    playbackSpeed = 1.25f,
                )

            val payload = delegate().snapshot()

            assertEquals(BackupSettingsPayload.FIELD_COUNT, payload.populatedCount())
            assertEquals(true, payload.useDynamicColor)
            assertEquals(false, payload.llmEnabled)
            assertEquals(true, payload.autoTitle)
            assertEquals(false, payload.autoSummary)
            assertEquals(true, payload.keyboardSaveRecordings)
            assertEquals(false, payload.keyboardLlmEnabled)
            // null processing mode is captured as the explicit "use global" sentinel.
            assertEquals("", payload.keyboardProcessingMode)
            assertEquals(2.5f, payload.microphoneGain)
            assertEquals("balanced", payload.recordingQuality)
            assertEquals("mp3", payload.outputFormat)
            assertEquals(1.25f, payload.playbackSpeed)
        }

    @Test
    fun `apply writes every populated preference through the public setters`() =
        runTest {
            val payload =
                BackupSettingsPayload(
                    useDynamicColor = true,
                    llmEnabled = false,
                    autoTitle = true,
                    autoSummary = true,
                    keyboardSaveRecordings = true,
                    keyboardLlmEnabled = false,
                    keyboardProcessingMode = "email",
                    microphoneGain = 3.0f,
                    recordingQuality = "low",
                    outputFormat = "wav",
                    playbackSpeed = 2.0f,
                )

            val applied = delegate().apply(payload)

            assertEquals(BackupSettingsPayload.FIELD_COUNT, applied)
            coVerify { dynamicColor.setUseDynamicColor(true) }
            coVerify { llmPreferences.setLlmEnabled(false) }
            coVerify { llmPreferences.setAutoTitle(true) }
            coVerify { llmPreferences.setAutoSummary(true) }
            coVerify { keyboardPreferences.setSaveKeyboardRecordings(true) }
            coVerify { keyboardPreferences.setLlmEnabled(false) }
            coVerify { keyboardPreferences.setDefaultProcessingMode("email") }
            coVerify { audioSettingsStore.setMicrophoneGain(3.0f) }
            coVerify { audioSettingsStore.setRecordingQualityPreset(RecordingQualityPreset.Low) }
            coVerify { audioSettingsStore.setOutputFormat(RecordingOutputFormat.WAV) }
            coVerify { audioSettingsStore.setPlaybackSpeed(2.0f) }
        }

    @Test
    fun `apply skips absent fields entirely`() =
        runTest {
            val applied = delegate().apply(BackupSettingsPayload(useDynamicColor = false))

            assertEquals(1, applied)
            coVerify(exactly = 1) { dynamicColor.setUseDynamicColor(false) }
            confirmVerified(dynamicColor)
            coVerify(exactly = 0) { keyboardPreferences.setDefaultProcessingMode(any()) }
            coVerify(exactly = 0) { audioSettingsStore.setMicrophoneGain(any()) }
        }

    @Test
    fun `empty keyboard mode restores the use-global setting`() =
        runTest {
            delegate().apply(BackupSettingsPayload(keyboardProcessingMode = ""))

            coVerify(exactly = 1) { keyboardPreferences.setDefaultProcessingMode(null) }
        }

    @Test
    fun `keyboard mode is remapped through the preset id map`() =
        runTest {
            delegate().apply(
                BackupSettingsPayload(keyboardProcessingMode = "user_old"),
                presetIdRemap = mapOf("user_old" to "user_new"),
            )

            coVerify(exactly = 1) { keyboardPreferences.setDefaultProcessingMode("user_new") }
        }

    @Test
    fun `unknown storage values fall back to defaults instead of failing`() =
        runTest {
            delegate().apply(BackupSettingsPayload(recordingQuality = "ultra-mega", outputFormat = "flac"))

            coVerify { audioSettingsStore.setRecordingQualityPreset(RecordingQualityPreset.DEFAULT) }
            coVerify { audioSettingsStore.setOutputFormat(RecordingOutputFormat.DEFAULT) }
        }
}
