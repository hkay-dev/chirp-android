package dev.chirpboard.app.feature.transcription.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.feature.transcription.WhisperModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

@HiltViewModel
class TranscriptionSettingsViewModel
    @Inject
    constructor(
        private val modelManager: WhisperModelManager,
        private val transcriptionRecovery: TranscriptionRecovery,
    ) : ViewModel() {
        data class UiState(
            val modelName: String = WhisperModelManager.MODEL_DISPLAY_NAME,
            val modelSizeMb: Int = WhisperModelManager.MODEL_SIZE_MB,
            val isDownloaded: Boolean = false,
            val isLoading: Boolean = false,
            val downloadProgress: Float = 0f,
            val currentFile: String = "",
            val errorMessage: String? = null,
            val showDeleteConfirmation: Boolean = false,
        )

        private val _uiState = MutableStateFlow(UiState())
        val uiState: StateFlow<UiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                modelManager.modelStatus.collect { status ->
                    when (status) {
                        is WhisperModelManager.ModelStatus.Ready -> {
                            _uiState.update {
                                it.copy(
                                    isDownloaded = true,
                                    isLoading = false,
                                    downloadProgress = 0f,
                                    errorMessage = null,
                                )
                            }
                        }

                        is WhisperModelManager.ModelStatus.NotDownloaded -> {
                            _uiState.update {
                                it.copy(
                                    isDownloaded = false,
                                    isLoading = false,
                                    downloadProgress = 0f,
                                )
                            }
                        }

                        is WhisperModelManager.ModelStatus.Downloading -> {
                            _uiState.update {
                                it.copy(
                                    isLoading = true,
                                    downloadProgress = status.progress,
                                )
                            }
                        }

                        is WhisperModelManager.ModelStatus.Error -> {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    errorMessage = status.message,
                                )
                            }
                        }
                    }
                }
            }

            modelManager.refreshStatus()
        }

        fun downloadModel() {
            if (_uiState.value.isLoading) return

            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, errorMessage = null, downloadProgress = 0f) }
                try {
                    modelManager.downloadModel {
                        transcriptionRecovery.recoverRecordingsWaitingForModel()
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    val message = e.message ?: "Download failed"
                    modelManager.markDownloadError(message)
                    _uiState.update { it.copy(errorMessage = message) }
                }
            }
        }

        fun showDeleteConfirmation() {
            _uiState.update { it.copy(showDeleteConfirmation = true) }
        }

        fun dismissDeleteConfirmation() {
            _uiState.update { it.copy(showDeleteConfirmation = false) }
        }

        fun deleteModel() {
            viewModelScope.launch {
                _uiState.update { it.copy(showDeleteConfirmation = false) }

                val success =
                    withContext(Dispatchers.IO) {
                        modelManager.deleteModel()
                    }

                if (!success) {
                    _uiState.update { it.copy(errorMessage = "Failed to delete model files") }
                }
            }
        }

        fun dismissError() {
            _uiState.update { it.copy(errorMessage = null) }
        }
    }
