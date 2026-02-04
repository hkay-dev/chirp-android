package dev.chirpboard.app.feature.llm.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.feature.llm.client.LlmClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LlmSettingsViewModel @Inject constructor(
    private val preferences: LlmPreferences,
    private val llmClient: LlmClient
) : ViewModel() {

    data class UiState(
        val apiKey: String = "",
        val isKeyConfigured: Boolean = false,
        val isTestingConnection: Boolean = false,
        val connectionTestResult: ConnectionTestResult? = null
    )

    sealed class ConnectionTestResult {
        data object Success : ConnectionTestResult()
        data class Error(val message: String) : ConnectionTestResult()
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.apiKey.collect { key ->
                _uiState.update {
                    it.copy(
                        apiKey = key ?: "",
                        isKeyConfigured = !key.isNullOrBlank()
                    )
                }
            }
        }
    }

    fun updateApiKey(key: String) {
        _uiState.update { it.copy(apiKey = key) }
    }

    fun saveApiKey() {
        viewModelScope.launch {
            preferences.setApiKey(_uiState.value.apiKey)
        }
    }

    fun clearApiKey() {
        viewModelScope.launch {
            preferences.clearApiKey()
            _uiState.update { it.copy(apiKey = "", connectionTestResult = null) }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingConnection = true, connectionTestResult = null) }
            
            val result = llmClient.process(
                text = "Hello",
                systemPrompt = "Reply with 'OK' if you can read this."
            )
            
            _uiState.update {
                it.copy(
                    isTestingConnection = false,
                    connectionTestResult = if (result.isSuccess) {
                        ConnectionTestResult.Success
                    } else {
                        ConnectionTestResult.Error(
                            result.exceptionOrNull()?.message ?: "Unknown error"
                        )
                    }
                )
            }
        }
    }

    fun dismissTestResult() {
        _uiState.update { it.copy(connectionTestResult = null) }
    }
}
