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
            val isSecureStorageAvailable: Boolean = true,
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
                _uiState.update { current ->
                    val apiKeyInput =
                        savedStateHandle.get<String>("apiKeyInput")
                            ?: current.apiKey.takeIf { it.isNotBlank() }
                            ?: storedApiKey
                    savedStateHandle["apiKeyInput"] = apiKeyInput
                    current.copy(
                        llmEnabled = preferences.getLlmEnabled(),
                        apiKey = apiKeyInput,
                        isKeyConfigured = preferences.hasApiKey(),
                        isSecureStorageAvailable = preferences.isSecureStorageAvailable(),
                        autoTitle = preferences.getAutoTitle(),
                        autoSummary = preferences.getAutoSummary(),
                    )
                }
            }
        }

        fun updateApiKey(key: String) {
            val normalized = key.trim()
            savedStateHandle["apiKeyInput"] = normalized
            _uiState.update { it.copy(apiKey = normalized) }
        }

        fun saveApiKey() {
            viewModelScope.launch {
                if (!preferences.isSecureStorageAvailable()) {
                    _uiState.update {
                        it.copy(connectionTestResult = ConnectionTestResult.Error("Secure storage unavailable on this device"))
                    }
                    return@launch
                }

                val apiKey = _uiState.value.apiKey.trim()
                if (apiKey.isBlank()) return@launch

                preferences.setApiKey(apiKey)
                val saved = preferences.hasApiKey()
                _uiState.update {
                    it.copy(
                        apiKey = apiKey,
                        isKeyConfigured = saved,
                        connectionTestResult =
                            if (saved) {
                                null
                            } else {
                                ConnectionTestResult.Error("Failed to save API key")
                            },
                    )
                }
            }
        }

        fun clearApiKey() {
            viewModelScope.launch {
                preferences.clearApiKey()
                savedStateHandle["apiKeyInput"] = ""
                _uiState.update {
                    it.copy(
                        apiKey = "",
                        isKeyConfigured = false,
                        connectionTestResult = null,
                    )
                }
            }
        }

        fun testConnection() {
            viewModelScope.launch {
                _uiState.update { it.copy(isTestingConnection = true, connectionTestResult = null) }

                if (!preferences.isSecureStorageAvailable()) {
                    _uiState.update {
                        it.copy(
                            isTestingConnection = false,
                            connectionTestResult = ConnectionTestResult.Error("Secure storage unavailable on this device"),
                        )
                    }
                    return@launch
                }

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
                if (!preferences.hasApiKey()) {
                    _uiState.update {
                        it.copy(
                            isTestingConnection = false,
                            connectionTestResult = ConnectionTestResult.Error("Failed to save API key"),
                        )
                    }
                    return@launch
                }

                val result =
                    llmClient.process(
                        text = "Hello",
                        systemPrompt = "Reply with 'OK' if you can read this.",
                    )

                _uiState.update {
                    it.copy(
                        isTestingConnection = false,
                        isKeyConfigured = preferences.hasApiKey(),
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
