package dev.chirpboard.app.backup

import dev.chirpboard.app.core.audio.AudioSettingsStore
import dev.chirpboard.app.core.audio.RecordingOutputFormat
import dev.chirpboard.app.core.audio.RecordingQualityPreset
import dev.chirpboard.app.core.preferences.KeyboardPreferences
import dev.chirpboard.app.core.ui.theme.DynamicColorPreference
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshots and restores the DataStore-backed app preferences covered by Backup & Restore.
 *
 * Restore goes through the same public setters the settings screens use, so every value is
 * range-clamped/normalized exactly once (gain coercion, playback-speed snapping, enum
 * fallback via storage values) and takes effect live — all consumers observe these flows.
 */
@Singleton
class SettingsBackupDelegate
    @Inject
    constructor(
        private val dynamicColorPreference: DynamicColorPreference,
        private val keyboardPreferences: KeyboardPreferences,
        private val audioSettingsStore: AudioSettingsStore,
        private val llmPreferences: LlmPreferences,
    ) {
        suspend fun snapshot(): BackupSettingsPayload {
            val audio = audioSettingsStore.currentSettings()
            return BackupSettingsPayload(
                useDynamicColor = dynamicColorPreference.useDynamicColor.first(),
                llmEnabled = llmPreferences.getLlmEnabled(),
                autoTitle = llmPreferences.getAutoTitle(),
                autoSummary = llmPreferences.getAutoSummary(),
                keyboardSaveRecordings = keyboardPreferences.saveKeyboardRecordings.first(),
                keyboardLlmEnabled = keyboardPreferences.llmEnabled.first(),
                // Empty string is the explicit "use the global setting" sentinel: a null field
                // in the payload means "not captured" and must not reset the preference.
                keyboardProcessingMode = keyboardPreferences.defaultProcessingMode.first().orEmpty(),
                microphoneGain = audio.microphoneGain,
                recordingQuality = audio.recordingQualityPreset.storageValue,
                outputFormat = audio.outputFormat.storageValue,
                playbackSpeed = audio.playbackSpeed,
            )
        }

        /**
         * Applies every captured (non-null) preference; absent fields leave the current value
         * untouched. [presetIdRemap] translates source-device custom-preset ids to the ids
         * they received on this device when the processing-presets section was also imported.
         * Returns the number of preferences applied.
         */
        suspend fun apply(
            payload: BackupSettingsPayload,
            presetIdRemap: Map<String, String> = emptyMap(),
        ): Int {
            var applied = 0

            payload.useDynamicColor?.let {
                dynamicColorPreference.setUseDynamicColor(it)
                applied++
            }
            payload.llmEnabled?.let {
                llmPreferences.setLlmEnabled(it)
                applied++
            }
            payload.autoTitle?.let {
                llmPreferences.setAutoTitle(it)
                applied++
            }
            payload.autoSummary?.let {
                llmPreferences.setAutoSummary(it)
                applied++
            }
            payload.keyboardSaveRecordings?.let {
                keyboardPreferences.setSaveKeyboardRecordings(it)
                applied++
            }
            payload.keyboardLlmEnabled?.let {
                keyboardPreferences.setLlmEnabled(it)
                applied++
            }
            payload.keyboardProcessingMode?.let { modeId ->
                val resolved = modeId.takeIf { it.isNotEmpty() }?.let { presetIdRemap[it] ?: it }
                keyboardPreferences.setDefaultProcessingMode(resolved)
                applied++
            }
            payload.microphoneGain?.let {
                audioSettingsStore.setMicrophoneGain(it)
                applied++
            }
            payload.recordingQuality?.let {
                audioSettingsStore.setRecordingQualityPreset(RecordingQualityPreset.fromStorageValue(it))
                applied++
            }
            payload.outputFormat?.let {
                audioSettingsStore.setOutputFormat(RecordingOutputFormat.fromStorageValue(it))
                applied++
            }
            payload.playbackSpeed?.let {
                audioSettingsStore.setPlaybackSpeed(it)
                applied++
            }

            return applied
        }
    }
