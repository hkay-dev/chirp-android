package dev.chirpboard.app.feature.llm.settings

import androidx.lifecycle.SavedStateHandle
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
class LlmSettingsViewModel
    @Inject
    constructor(
        private val preferences: LlmPreferences,
        private val llmClient: LlmClient,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        data class UiState(
            val llmEnabled: Boolean = true,
            val apiKey: String = "",
            val isKeyConfigured: Boolean = false,
            val isTestingConnection: Boolean = false,
            val connectionTestResult: ConnectionTestResult? = null,
            val autoTitle: Boolean = false,
            val autoSummary: Boolean = false,
        )

        sealed class ConnectionTestResult {
            data object Success : ConnectionTestResult()

            data class Error(
                val message: String,
            ) : ConnectionTestResult()
        }

        private val _uiState = MutableStateFlow(UiState())
        val uiState: StateFlow<UiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                val storedApiKey = preferences.fetchApiKey().orEmpty()
                val apiKeyInput = savedStateHandle.get<String>("apiKeyInput") ?: storedApiKey
                _uiState.update {
                    it.copy(
                        llmEnabled = preferences.getLlmEnabled(),
                        apiKey = apiKeyInput,
                        isKeyConfigured = apiKeyInput.isNotBlank(),
                        autoTitle = preferences.getAutoTitle(),
                        autoSummary = preferences.getAutoSummary(),
                    )
                }
            }
        }

        fun updateApiKey(key: String) {
            savedStateHandle["apiKeyInput"] = key
            _uiState.update { it.copy(apiKey = key, isKeyConfigured = key.isNotBlank()) }
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

                val apiKey = _uiState.value.apiKey.trim()
                if (apiKey.isBlank()) {
                    _uiState.update {
                        it.copy(
                            isTestingConnection = false,
                            connectionTestResult = ConnectionTestResult.Error("API key not configured"),
                        )
                    }
                    return@launch
                }

                preferences.setApiKey(apiKey)

                val result =
                    llmClient.process(
                        text = "Hello",
                        systemPrompt = "Reply with 'OK' if you can read this.",
                    )

                _uiState.update {
                    it.copy(
                        isTestingConnection = false,
                        connectionTestResult =
                            if (result.isSuccess) {
                                ConnectionTestResult.Success
                            } else {
                                ConnectionTestResult.Error(
                                    result.exceptionOrNull()?.message ?: "Unknown error",
                                )
                            },
                    )
                }
            }
        }

        fun dismissTestResult() {
            _uiState.update { it.copy(connectionTestResult = null) }
        }

        fun setAutoTitle(enabled: Boolean) {
            viewModelScope.launch {
                preferences.setAutoTitle(enabled)
                _uiState.update { it.copy(autoTitle = enabled) }
            }
        }

        fun setAutoSummary(enabled: Boolean) {
            viewModelScope.launch {
                preferences.setAutoSummary(enabled)
                _uiState.update { it.copy(autoSummary = enabled) }
            }
        }

        fun setLlmEnabled(enabled: Boolean) {
            viewModelScope.launch {
                preferences.setLlmEnabled(enabled)
                _uiState.update { it.copy(llmEnabled = enabled) }
            }
        }
    }
