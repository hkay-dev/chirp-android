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
        val dictationHistoryEnabled: Boolean = true,
        val floatingMicBubbleEnabled: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // The typed combine overloads stop at five flows, so the sixth preference is
            // layered on with a nested combine.
            combine(
                combine(
                    keyboardPreferences.saveKeyboardRecordings,
                    keyboardPreferences.llmEnabled,
                    keyboardPreferences.defaultProcessingMode,
                    keyboardPreferences.quickInputNotificationTimeoutMs,
                    keyboardPreferences.dictationHistoryEnabled,
                ) { saveRecordings, llmEnabled, processingMode, notificationTimeoutMs, dictationHistoryEnabled ->
                    UiState(
                        saveKeyboardRecordings = saveRecordings,
                        llmEnabled = llmEnabled,
                        defaultProcessingMode = processingMode,
                        quickInputNotificationTimeoutMs = notificationTimeoutMs,
                        dictationHistoryEnabled = dictationHistoryEnabled,
                    )
                },
                keyboardPreferences.floatingMicBubbleEnabled,
            ) { state, floatingMicBubbleEnabled ->
                state.copy(floatingMicBubbleEnabled = floatingMicBubbleEnabled)
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

    fun toggleDictationHistoryEnabled() {
        viewModelScope.launch {
            keyboardPreferences.setDictationHistoryEnabled(!_uiState.value.dictationHistoryEnabled)
        }
    }

    fun setFloatingMicBubbleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            keyboardPreferences.setFloatingMicBubbleEnabled(enabled)
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
