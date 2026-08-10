package dev.chirpboard.app.feature.llm.settings

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.feature.llm.R
import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.llm.client.TranscriptLlmContext
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
        // I18N-08: settings status/error copy comes from resources.
        @ApplicationContext private val appContext: Context,
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
            /** SEC-2: the secure store was wiped after an undecryptable keyset; keys must be re-entered. */
            val secureStorageWasReset: Boolean = false,
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

        // LIF-17 (accepted, documented): the API-key DRAFT is mirrored into SavedStateHandle so
        // typed-but-unsaved input survives process death. That places it (plaintext) in the
        // activity's saved-instance Bundle held by system_server — strictly less exposure than
        // disk, cleared when the field is saved/cleared, and judged acceptable for a personal
        // sideloaded app over silently losing a long pasted key.
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
                        it.copy(connectionTestResult = ConnectionTestResult.Error(appContext.getString(R.string.llm_error_secure_storage_unavailable)))
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
                                ConnectionTestResult.Error(appContext.getString(R.string.llm_error_key_save_failed))
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
                            connectionTestResult = ConnectionTestResult.Error(appContext.getString(R.string.llm_error_secure_storage_unavailable)),
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
                            connectionTestResult = ConnectionTestResult.Error(appContext.getString(R.string.llm_error_key_not_configured)),
                        )
                    }
                    return@launch
                }

                // The client reads its key from the store, so the draft has to be written
                // before the probe — but a failed test must not leave that unvalidated
                // draft as the live credential for background workers. Remember what was
                // stored so it can be put back if the test fails.
                val previousKey = preferences.fetchApiKeyFor(provider)
                preferences.setApiKeyFor(provider, apiKey)
                if (!preferences.hasApiKeyFor(provider)) {
                    _uiState.update {
                        it.copy(
                            isTestingConnection = false,
                            connectionTestResult = ConnectionTestResult.Error(appContext.getString(R.string.llm_error_key_save_failed)),
                        )
                    }
                    return@launch
                }

                val result =
                    llmClient.process(
                        context = TranscriptLlmContext("Hello"),
                        systemPrompt = "Reply with 'OK' if you can read this.",
                    )

                if (result.isFailure && previousKey != apiKey) {
                    if (previousKey.isNullOrBlank()) {
                        preferences.clearApiKeyFor(provider)
                    } else {
                        preferences.setApiKeyFor(provider, previousKey)
                    }
                }

                _uiState.update {
                    it.copy(
                        isTestingConnection = false,
                        isKeyConfigured = preferences.hasApiKeyFor(provider),
                        configuredKeyCount = preferences.countConfiguredApiKeys(),
                        connectionTestResult =
                            if (result.isSuccess) {
                                ConnectionTestResult.Success
                            } else {
                                // I18N-05: raw client errors are developer diagnostics; show
                                // classified, actionable copy and keep the details in logs.
                                val error = result.exceptionOrNull()
                                Log.w("LlmSettingsVM", "Connection test failed", error)
                                ConnectionTestResult.Error(connectionTestFailureMessage(appContext, error))
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
                        backupMessage =
                            StatusMessage.Error(
                                appContext.getString(R.string.llm_error_passphrase_too_short, MIN_PASSPHRASE_LENGTH),
                            ),
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
                                        appContext.resources.getQuantityString(
                                            R.plurals.llm_backup_export_success,
                                            keyCount,
                                            keyCount,
                                        ),
                                    ),
                            )
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            // I18N-05: never surface raw exception text.
                            Log.w("LlmSettingsVM", "Key backup failed", error)
                            it.copy(
                                backupMessage =
                                    StatusMessage.Error(appContext.getString(R.string.llm_backup_export_failed)),
                            )
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
                                        appContext.resources.getQuantityString(
                                            R.plurals.llm_backup_import_success,
                                            keyCount,
                                            keyCount,
                                        ),
                                    ),
                            )
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            // I18N-05: never surface raw exception text.
                            Log.w("LlmSettingsVM", "Key restore failed", error)
                            it.copy(
                                backupMessage =
                                    StatusMessage.Error(appContext.getString(R.string.llm_backup_import_failed)),
                            )
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
            // update {} is a compare-and-set retry loop, so its lambda can run more than once
            // under concurrent state changes. Everything with a side effect — the
            // SavedStateHandle write and especially the one-shot
            // consumeSecureStorageResetNotice() read, which would be swallowed on a retry —
            // must happen exactly once, out here; only pure state assembly goes inside.
            val provider = preferences.getActiveProvider()
            val storedApiKey = preferences.fetchApiKeyFor(provider).orEmpty()
            val apiKeyInput =
                savedStateHandle.get<String>(apiKeyInputKey(provider))
                    ?: _uiState.value.apiKey.takeIf { it.isNotBlank() }
                    ?: storedApiKey
            savedStateHandle[apiKeyInputKey(provider)] = apiKeyInput
            val llmEnabled = preferences.getLlmEnabled()
            val selectedModelId = preferences.getModelFor(provider)
            val isKeyConfigured = preferences.hasApiKeyFor(provider)
            val configuredKeyCount = preferences.countConfiguredApiKeys()
            val isSecureStorageAvailable = preferences.isSecureStorageAvailable()
            val secureStorageResetNoticed = preferences.consumeSecureStorageResetNotice()
            val autoTitle = preferences.getAutoTitle()
            val autoSummary = preferences.getAutoSummary()
            _uiState.update { current ->
                current.copy(
                    llmEnabled = llmEnabled,
                    activeProvider = provider,
                    availableModels = modelsFor(provider),
                    selectedModelId = selectedModelId,
                    apiKey = apiKeyInput,
                    isKeyConfigured = isKeyConfigured,
                    configuredKeyCount = configuredKeyCount,
                    isSecureStorageAvailable = isSecureStorageAvailable,
                    // OR with the current value so a mid-session refresh cannot clear a
                    // notice the user has not dismissed yet.
                    secureStorageWasReset = current.secureStorageWasReset || secureStorageResetNoticed,
                    autoTitle = autoTitle,
                    autoSummary = autoSummary,
                )
            }
        }

        fun dismissSecureStorageResetNotice() {
            _uiState.update { it.copy(secureStorageWasReset = false) }
        }

        private fun apiKeyInputKey(provider: LlmProvider): String = "apiKeyInput_${provider.id}"
    }

/**
 * I18N-05: classify a connection-test failure into actionable copy. Network-shaped failures
 * point at connectivity; everything else points at the key/model configuration.
 */
internal fun connectionTestFailureMessage(
    context: Context,
    error: Throwable?,
): String =
    if (error is java.io.IOException) {
        context.getString(R.string.llm_error_connection_network)
    } else {
        context.getString(R.string.llm_error_connection_rejected)
    }
