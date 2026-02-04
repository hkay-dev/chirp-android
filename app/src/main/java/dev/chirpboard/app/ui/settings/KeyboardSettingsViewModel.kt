package dev.chirpboard.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.feature.keyboard.KeyboardPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class KeyboardSettingsViewModel @Inject constructor(
    private val keyboardPreferences: KeyboardPreferences
) : ViewModel() {

    data class UiState(
        val saveKeyboardRecordings: Boolean = false,
        val llmEnabled: Boolean = true,
        val defaultProcessingMode: String? = null,
        val microphoneGain: Float = 1.0f
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                keyboardPreferences.saveKeyboardRecordings,
                keyboardPreferences.llmEnabled,
                keyboardPreferences.defaultProcessingMode,
                keyboardPreferences.microphoneGain
            ) { saveRecordings, llmEnabled, processingMode, micGain ->
                UiState(
                    saveKeyboardRecordings = saveRecordings,
                    llmEnabled = llmEnabled,
                    defaultProcessingMode = processingMode,
                    microphoneGain = micGain
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun toggleSaveRecordings() {
        viewModelScope.launch {
            keyboardPreferences.setSaveKeyboardRecordings(!_uiState.value.saveKeyboardRecordings)
        }
    }

    fun toggleLlmEnabled() {
        viewModelScope.launch {
            keyboardPreferences.setLlmEnabled(!_uiState.value.llmEnabled)
        }
    }

    fun setProcessingMode(mode: String?) {
        viewModelScope.launch {
            keyboardPreferences.setDefaultProcessingMode(mode)
        }
    }

    fun setMicrophoneGain(gain: Float) {
        viewModelScope.launch {
            keyboardPreferences.setMicrophoneGain(gain)
        }
    }
}
