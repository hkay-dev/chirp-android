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

        /** Persisted manual selection key so the picker can mark the chosen device. */
        val manualDeviceKey: StateFlow<String?> =
            audioSettingsStore.settings
                .map { it.manualDeviceAddress }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = null,
                )

        init {
            refreshInputDevices()
            // Hot-plug refresh: without this the device list is a one-shot snapshot and a
            // mic plugged in while the screen is open never appears.
            viewModelScope.launch {
                inputDeviceSelector.devicesChangedTick.collect {
                    refreshInputDevices()
                }
            }
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
                when (policy) {
                    // Atomic single-edit flip; leaves the dormant manual key in place so a
                    // later switch back to Manual restores the prior selection verbatim.
                    AudioInputDevicePolicy.Automatic -> audioSettingsStore.selectAutomatic()
                    // Manual without a chosen device yet, and PreferBuiltIn, only flip the
                    // policy; the manual key is written when a device row is tapped.
                    AudioInputDevicePolicy.PreferBuiltIn,
                    AudioInputDevicePolicy.Manual,
                    -> audioSettingsStore.setInputDevicePolicy(policy)
                }
            }
        }

        /**
         * Persists a manual device selection by its stable [AudioInputDeviceSummary.selectionKey]
         * (address, or a type+name composite for blank-address Bluetooth/wired devices —
         * the previous transient-id fallback could never be resolved again) plus its display
         * name, so the preference can be named even while the device is disconnected.
         */
        fun setManualInputDevice(device: AudioInputDeviceSummary) {
            viewModelScope.launch {
                // Single atomic edit so a capture racing the tap can never read the new key
                // under the old (automatic) policy, and the picker sees one `settings` emission.
                audioSettingsStore.selectManualDevice(device.selectionKey, device.productName)
            }
        }

        /**
         * Re-enumerates devices after a BLUETOOTH_CONNECT grant so real Bluetooth names
         * (and addresses) replace the type-label placeholders app-wide.
         */
        fun onBluetoothPermissionGranted() {
            inputDeviceSelector.refreshDevices()
            refreshInputDevices()
        }
    }
