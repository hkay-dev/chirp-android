package dev.chirpboard.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.feature.keyboard.KeyboardPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AudioSettingsViewModel @Inject constructor(
    private val keyboardPreferences: KeyboardPreferences
) : ViewModel() {

    val microphoneGain: StateFlow<Float> = keyboardPreferences.microphoneGain
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 1.0f
        )

    fun setMicrophoneGain(gain: Float) {
        viewModelScope.launch {
            keyboardPreferences.setMicrophoneGain(gain)
        }
    }
}
