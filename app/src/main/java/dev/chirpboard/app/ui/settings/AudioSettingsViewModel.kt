package dev.chirpboard.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.core.audio.RecordingQualityPreset
import dev.chirpboard.app.core.audio.SAVED_RECORDING_FORMAT_LABEL
import dev.chirpboard.app.feature.keyboard.KeyboardPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AudioSettingsViewModel @Inject constructor(
    private val keyboardPreferences: KeyboardPreferences,
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

    val savedFormatLabel: String = SAVED_RECORDING_FORMAT_LABEL

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
}