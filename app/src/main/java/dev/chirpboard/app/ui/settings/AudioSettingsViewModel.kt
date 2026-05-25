package dev.chirpboard.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.core.audio.AudioInputDevicePolicy
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.AudioInputDeviceSummary
import dev.chirpboard.app.core.audio.AudioSettingsStore
import dev.chirpboard.app.core.audio.RecordingOutputFormat
import dev.chirpboard.app.core.audio.RecordingQualityPreset
import dev.chirpboard.app.core.preferences.KeyboardPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AudioSettingsViewModel
    @Inject
    constructor(
        private val keyboardPreferences: KeyboardPreferences,
        private val audioSettingsStore: AudioSettingsStore,
        private val inputDeviceSelector: AudioInputDeviceSelector,
    ) : ViewModel() {
        val microphoneGain: StateFlow<Float> =
            keyboardPreferences.microphoneGain.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = 1.0f,
            )

        val recordingQualityPreset: StateFlow<RecordingQualityPreset> =
            keyboardPreferences.recordingQualityPreset.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = RecordingQualityPreset.DEFAULT,
            )

        val outputFormat: StateFlow<RecordingOutputFormat> =
            keyboardPreferences.outputFormat.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = RecordingOutputFormat.DEFAULT,
            )

        val inputDevicePolicy: StateFlow<AudioInputDevicePolicy> =
            audioSettingsStore.settings
                .map { it.inputDevicePolicy }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = AudioInputDevicePolicy.DEFAULT,
                )

        private val _availableInputDevices = MutableStateFlow<List<AudioInputDeviceSummary>>(emptyList())
        val availableInputDevices: StateFlow<List<AudioInputDeviceSummary>> = _availableInputDevices.asStateFlow()

        val activeInputDeviceLabel: StateFlow<String?> = inputDeviceSelector.activeDeviceLabel

        init {
            refreshInputDevices()
        }

        fun refreshInputDevices() {
            viewModelScope.launch {
                _availableInputDevices.value = inputDeviceSelector.listInputDevices()
            }
        }

        fun setMicrophoneGain(gain: Float) {
            viewModelScope.launch {
                keyboardPreferences.setMicrophoneGain(gain)
            }
        }

        fun setRecordingQualityPreset(preset: RecordingQualityPreset) {
            viewModelScope.launch {
                keyboardPreferences.setRecordingQualityPreset(preset)
            }
        }

        fun setOutputFormat(format: RecordingOutputFormat) {
            viewModelScope.launch {
                keyboardPreferences.setOutputFormat(format)
            }
        }

        fun setInputDevicePolicy(policy: AudioInputDevicePolicy) {
            viewModelScope.launch {
                audioSettingsStore.setInputDevicePolicy(policy)
            }
        }

        fun setManualInputDevice(address: String?) {
            viewModelScope.launch {
                audioSettingsStore.setManualDeviceAddress(address)
                audioSettingsStore.setInputDevicePolicy(AudioInputDevicePolicy.Manual)
            }
        }
    }
