package dev.chirpboard.app.feature.transcription.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.feature.transcription.SpeechModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class TranscriptionSettingsViewModel
    @Inject
    constructor(
        private val modelManager: SpeechModelManager,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        companion object {
            internal const val KEY_AUTO_DOWNLOAD = "autoDownload"
            internal const val KEY_AUTO_DOWNLOAD_CONSUMED = "autoDownloadConsumed"
            internal const val KEY_AWAITING_ALL_FILES_GRANT = "awaitingAllFilesGrant"
        }

        data class UiState(
            val modelName: String = SpeechModelManager.MODEL_DISPLAY_NAME,
            val modelSizeMb: Int = SpeechModelManager.MODEL_SIZE_MB,
            val downloadedSizeMb: Int? = null,
            val isDownloaded: Boolean = false,
            val isLoading: Boolean = false,
            val isWaitingForNetwork: Boolean = false,
            val downloadProgress: Float = 0f,
            val currentFile: String = "",
            val errorMessage: String? = null,
            val showDeleteConfirmation: Boolean = false,
            val showStorageChoice: Boolean = false,
        )

        private val _uiState = MutableStateFlow(UiState())
        val uiState: StateFlow<UiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch {
                modelManager.modelStatus.collect { status ->
                    when (status) {
                        is SpeechModelManager.ModelStatus.Ready -> {
                            _uiState.update {
                                it.copy(
                                    isDownloaded = true,
                                    isLoading = false,
                                    isWaitingForNetwork = false,
                                    downloadProgress = 0f,
                                    currentFile = "",
                                    errorMessage = null,
                                )
                            }
                            loadDownloadedSize()
                        }

                        is SpeechModelManager.ModelStatus.NotDownloaded -> {
                            _uiState.update {
                                it.copy(
                                    isDownloaded = false,
                                    isLoading = false,
                                    isWaitingForNetwork = false,
                                    downloadProgress = 0f,
                                    currentFile = "",
                                    downloadedSizeMb = null,
                                )
                            }
                        }

                        is SpeechModelManager.ModelStatus.Downloading -> {
                            _uiState.update {
                                it.copy(
                                    isDownloaded = false,
                                    isLoading = true,
                                    isWaitingForNetwork = false,
                                    downloadProgress = status.progress,
                                    currentFile = status.file,
                                    errorMessage = null,
                                )
                            }
                        }

                        is SpeechModelManager.ModelStatus.WaitingForNetwork -> {
                            _uiState.update {
                                it.copy(
                                    isDownloaded = false,
                                    isLoading = true,
                                    isWaitingForNetwork = true,
                                    currentFile = "",
                                    errorMessage = null,
                                )
                            }
                        }

                        is SpeechModelManager.ModelStatus.Error -> {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    isWaitingForNetwork = false,
                                    errorMessage = status.message,
                                )
                            }
                        }
                    }
                }
            }

            modelManager.refreshStatus()
        }

        /**
         * One-shot consumption of the `autoDownload` navigation argument (LIF-06/ERR-3):
         * returns true exactly once per navigation, never again after rotation, process
         * death + restore, or an error — auto-retry after a failed download is forbidden.
         */
        fun consumePendingAutoDownload(): Boolean {
            if (savedStateHandle.get<Boolean>(KEY_AUTO_DOWNLOAD) != true) return false
            if (savedStateHandle.get<Boolean>(KEY_AUTO_DOWNLOAD_CONSUMED) == true) return false
            savedStateHandle[KEY_AUTO_DOWNLOAD_CONSUMED] = true
            return true
        }

        /** PLT-07: rationale dialog before bouncing the user to the All-files-access toggle. */
        fun showStorageChoice() {
            _uiState.update { it.copy(showStorageChoice = true) }
        }

        fun dismissStorageChoice() {
            _uiState.update { it.copy(showStorageChoice = false) }
        }

        /**
         * The user chose "Allow access" and is being sent to system settings; remember that
         * across process death so the download auto-starts when they come back with the
         * grant (PLT-07).
         */
        fun onAllFilesAccessRequested() {
            _uiState.update { it.copy(showStorageChoice = false) }
            savedStateHandle[KEY_AWAITING_ALL_FILES_GRANT] = true
        }

        /**
         * Called on every screen resume. If the user just returned from the
         * All-files-access settings page with the grant, start the download they asked for;
         * if they declined, do nothing (no nagging — the choice dialog reappears on the
         * next explicit Download tap).
         */
        fun onResumed(hasAllFilesAccess: Boolean) {
            if (savedStateHandle.get<Boolean>(KEY_AWAITING_ALL_FILES_GRANT) != true) return
            savedStateHandle[KEY_AWAITING_ALL_FILES_GRANT] = false
            val state = _uiState.value
            if (hasAllFilesAccess && !state.isDownloaded && !state.isLoading) {
                downloadModel()
            }
        }

        fun downloadModel(preferInternalStorage: Boolean = false) {
            val state = _uiState.value
            if (state.isLoading || state.isDownloaded) return
            _uiState.update {
                it.copy(errorMessage = null, downloadProgress = 0f, showStorageChoice = false)
            }
            modelManager.requestDownload(preferInternalStorage)
        }

        fun cancelDownload() {
            modelManager.cancelDownload()
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

        private fun loadDownloadedSize() {
            viewModelScope.launch {
                val sizeMb =
                    try {
                        (modelManager.getDownloadedSize() / (1024L * 1024L)).toInt()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                _uiState.update { it.copy(downloadedSizeMb = sizeMb?.takeIf { mb -> mb > 0 }) }
            }
        }
    }
