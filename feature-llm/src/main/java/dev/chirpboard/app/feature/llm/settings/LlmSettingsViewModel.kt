package dev.chirpboard.app.feature.llm.settings

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.feature.llm.client.LlmClient
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LlmSettingsViewModel
    @Inject
    constructor(
        private val preferences: LlmSettingsStore,
        private val backupManager: LlmApiKeyBackupManager,
        private val llmClient: LlmClient,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        data class UiState(
            val llmEnabled: Boolean = true,
            val activeProvider: LlmProvider = LlmProvider.GEMINI,
            val availableModels: List<LlmModelOption> = modelsFor(LlmProvider.GEMINI),
            val selectedModelId: String = defaultModelFor(LlmProvider.GEMINI),
            val apiKey: String = "",
            val isKeyConfigured: Boolean = false,
            val configuredKeyCount: Int = 0,
            val isSecureStorageAvailable: Boolean = true,
            val isTestingConnection: Boolean = false,
            val connectionTestResult: ConnectionTestResult? = null,
            val backupMessage: StatusMessage? = null,
            val passphraseDialog: LlmPassphraseDialogMode? = null,
            val autoTitle: Boolean = false,
            val autoSummary: Boolean = false,
        )

        sealed class ConnectionTestResult {
            data object Success : ConnectionTestResult()

            data class Error(
                val message: String,
            ) : ConnectionTestResult()
        }

        sealed interface StatusMessage {
            val text: String

            data class Success(
                override val text: String,
            ) : StatusMessage

            data class Error(
                override val text: String,
            ) : StatusMessage
        }

        sealed interface FilePickerRequest {
            data class Save(
                val suggestedName: String,
            ) : FilePickerRequest

            data object Open : FilePickerRequest
        }

        private val _uiState = MutableStateFlow(UiState())
        val uiState: StateFlow<UiState> = _uiState.asStateFlow()

        private val _filePickerRequest = Channel<FilePickerRequest>(Channel.BUFFERED)
        val filePickerRequest = _filePickerRequest.receiveAsFlow()

        private var pendingPassphrase: CharArray? = null

        init {
            viewModelScope.launch {
                refreshFromPreferences()
            }
        }

        fun setActiveProvider(provider: LlmProvider) {
            if (provider == _uiState.value.activeProvider) return

            viewModelScope.launch {
                preferences.setActiveProvider(provider)
                val storedApiKey = preferences.fetchApiKeyFor(provider).orEmpty()
                val apiKeyInput = savedStateHandle.get<String>(apiKeyInputKey(provider)) ?: storedApiKey
                savedStateHandle[apiKeyInputKey(provider)] = apiKeyInput
                _uiState.update {
                    it.copy(
                        activeProvider = provider,
                        availableModels = modelsFor(provider),
                        selectedModelId = preferences.getModelFor(provider),
                        apiKey = apiKeyInput,
                        isKeyConfigured = preferences.hasApiKeyFor(provider),
                        configuredKeyCount = preferences.countConfiguredApiKeys(),
                        connectionTestResult = null,
                    )
                }
            }
        }

        fun setSelectedModel(modelId: String) {
            val provider = _uiState.value.activeProvider
            preferences.setModelFor(provider, modelId)
            _uiState.update { it.copy(selectedModelId = preferences.getModelFor(provider)) }
        }

        fun updateApiKey(key: String) {
            val provider = _uiState.value.activeProvider
            val normalized = key.trim()
            savedStateHandle[apiKeyInputKey(provider)] = normalized
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

                val provider = _uiState.value.activeProvider
                val apiKey = _uiState.value.apiKey.trim()
                if (apiKey.isBlank()) return@launch

                preferences.setApiKeyFor(provider, apiKey)
                val saved = preferences.hasApiKeyFor(provider)
                _uiState.update {
                    it.copy(
                        apiKey = apiKey,
                        isKeyConfigured = saved,
                        configuredKeyCount = preferences.countConfiguredApiKeys(),
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
                val provider = _uiState.value.activeProvider
                preferences.clearApiKeyFor(provider)
                savedStateHandle[apiKeyInputKey(provider)] = ""
                _uiState.update {
                    it.copy(
                        apiKey = "",
                        isKeyConfigured = false,
                        configuredKeyCount = preferences.countConfiguredApiKeys(),
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

                val provider = _uiState.value.activeProvider
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

                preferences.setApiKeyFor(provider, apiKey)
                if (!preferences.hasApiKeyFor(provider)) {
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
                        isKeyConfigured = preferences.hasApiKeyFor(provider),
                        configuredKeyCount = preferences.countConfiguredApiKeys(),
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

        fun dismissBackupMessage() {
            _uiState.update { it.copy(backupMessage = null) }
        }

        fun startBackup() {
            _uiState.update { it.copy(passphraseDialog = LlmPassphraseDialogMode.Backup, backupMessage = null) }
        }

        fun startRestore() {
            _uiState.update { it.copy(passphraseDialog = LlmPassphraseDialogMode.Restore, backupMessage = null) }
        }

        fun cancelPassphraseDialog() {
            _uiState.update { it.copy(passphraseDialog = null) }
        }

        fun submitPassphrase(passphrase: String) {
            if (passphrase.length < MIN_PASSPHRASE_LENGTH) {
                _uiState.update {
                    it.copy(
                        backupMessage = StatusMessage.Error("Passphrase must be at least $MIN_PASSPHRASE_LENGTH characters"),
                        passphraseDialog = null,
                    )
                }
                return
            }

            pendingPassphrase = passphrase.toCharArray()
            val mode = _uiState.value.passphraseDialog
            _uiState.update { it.copy(passphraseDialog = null) }

            viewModelScope.launch {
                when (mode) {
                    LlmPassphraseDialogMode.Backup -> {
                        _filePickerRequest.send(
                            FilePickerRequest.Save(backupManager.suggestedBackupFileName()),
                        )
                    }

                    LlmPassphraseDialogMode.Restore -> {
                        _filePickerRequest.send(FilePickerRequest.Open)
                    }

                    null -> pendingPassphrase = null
                }
            }
        }

        fun completeBackup(uri: Uri) {
            val passphrase = pendingPassphrase ?: return
            pendingPassphrase = null

            viewModelScope.launch {
                val result = backupManager.exportToUri(uri, passphrase)
                passphrase.fill('\u0000')

                result.fold(
                    onSuccess = { keyCount ->
                        _uiState.update {
                            it.copy(
                                backupMessage =
                                    StatusMessage.Success(
                                        "Backed up $keyCount provider ${if (keyCount == 1) "key" else "keys"}",
                                    ),
                            )
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(backupMessage = StatusMessage.Error(error.message ?: "Backup failed"))
                        }
                    },
                )
            }
        }

        fun completeRestore(uri: Uri) {
            val passphrase = pendingPassphrase ?: return
            pendingPassphrase = null

            viewModelScope.launch {
                val result = backupManager.importFromUri(uri, passphrase)
                passphrase.fill('\u0000')

                result.fold(
                    onSuccess = { keyCount ->
                        refreshFromPreferences()
                        _uiState.update {
                            it.copy(
                                backupMessage =
                                    StatusMessage.Success(
                                        "Restored $keyCount provider ${if (keyCount == 1) "key" else "keys"}",
                                    ),
                            )
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(backupMessage = StatusMessage.Error(error.message ?: "Restore failed"))
                        }
                    },
                )
            }
        }

        fun cancelPendingBackupOperation() {
            pendingPassphrase?.fill('\u0000')
            pendingPassphrase = null
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

        private suspend fun refreshFromPreferences() {
            val provider = preferences.getActiveProvider()
            val storedApiKey = preferences.fetchApiKeyFor(provider).orEmpty()
            _uiState.update { current ->
                val apiKeyInput =
                    savedStateHandle.get<String>(apiKeyInputKey(provider))
                        ?: current.apiKey.takeIf { it.isNotBlank() }
                        ?: storedApiKey
                savedStateHandle[apiKeyInputKey(provider)] = apiKeyInput
                current.copy(
                    llmEnabled = preferences.getLlmEnabled(),
                    activeProvider = provider,
                    availableModels = modelsFor(provider),
                    selectedModelId = preferences.getModelFor(provider),
                    apiKey = apiKeyInput,
                    isKeyConfigured = preferences.hasApiKeyFor(provider),
                    configuredKeyCount = preferences.countConfiguredApiKeys(),
                    isSecureStorageAvailable = preferences.isSecureStorageAvailable(),
                    autoTitle = preferences.getAutoTitle(),
                    autoSummary = preferences.getAutoSummary(),
                )
            }
        }

        private fun apiKeyInputKey(provider: LlmProvider): String = "apiKeyInput_${provider.id}"
    }
