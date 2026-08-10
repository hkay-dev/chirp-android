package dev.chirpboard.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.core.preferences.DEFAULT_QUICK_INPUT_NOTIFICATION_TIMEOUT_MS
import dev.chirpboard.app.core.preferences.KeyboardPreferences
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
        val quickInputNotificationTimeoutMs: Long = DEFAULT_QUICK_INPUT_NOTIFICATION_TIMEOUT_MS,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                keyboardPreferences.saveKeyboardRecordings,
                keyboardPreferences.llmEnabled,
                keyboardPreferences.defaultProcessingMode,
                keyboardPreferences.quickInputNotificationTimeoutMs,
            ) { saveRecordings, llmEnabled, processingMode, notificationTimeoutMs ->
                UiState(
                    saveKeyboardRecordings = saveRecordings,
                    llmEnabled = llmEnabled,
                    defaultProcessingMode = processingMode,
                    quickInputNotificationTimeoutMs = notificationTimeoutMs,
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

    fun setQuickInputNotificationTimeoutMs(timeoutMs: Long) {
        viewModelScope.launch {
            keyboardPreferences.setQuickInputNotificationTimeoutMs(timeoutMs)
        }
    }
}
