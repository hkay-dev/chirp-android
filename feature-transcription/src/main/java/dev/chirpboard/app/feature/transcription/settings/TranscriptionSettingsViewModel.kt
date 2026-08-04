package dev.chirpboard.app.feature.transcription.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.core.transcription.CloudFileTranscriptionProvider
import dev.chirpboard.app.core.transcription.CloudTranscriptionConfigurationStatus
import dev.chirpboard.app.core.transcription.TranscriptionEngine
import dev.chirpboard.app.core.transcription.TranscriptionRoutingStore
import dev.chirpboard.app.core.transcription.LocalSpeechModelActivationResult
import dev.chirpboard.app.core.transcription.LocalSpeechBackend
import dev.chirpboard.app.core.transcription.LocalSpeechComputeBackend
import dev.chirpboard.app.core.transcription.LocalSpeechComputeBackendActivationResult
import dev.chirpboard.app.core.transcription.LocalSpeechModelId
import dev.chirpboard.app.core.transcription.LocalSpeechModelInfo
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
        private val transcriptionRoutingStore: TranscriptionRoutingStore,
        private val cloudTranscriber: CloudFileTranscriptionProvider,
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
            val selectedEngine: TranscriptionEngine = TranscriptionEngine.LOCAL_PARAKEET,
            val availableLocalModels: List<LocalSpeechModelInfo> = emptyList(),
            val selectedLocalModel: LocalSpeechModelId = LocalSpeechModelId.PARAKEET_TDT_600M,
            val managedLocalModel: LocalSpeechModelId = LocalSpeechModelId.PARAKEET_TDT_600M,
            val selectedComputeBackend: LocalSpeechComputeBackend = LocalSpeechComputeBackend.CPU,
            val computeBackendNotice: String? = null,
            val cloudConfigurationStatus: CloudTranscriptionConfigurationStatus =
                CloudTranscriptionConfigurationStatus.AUTHENTICATION_MISSING,
        )

        private val _uiState =
            MutableStateFlow(
                UiState(
                    availableLocalModels = modelManager.availableModels,
                    selectedLocalModel = modelManager.selectedModel.value,
                    managedLocalModel = modelManager.managedModel.value,
                    selectedComputeBackend = modelManager.selectedComputeBackend.value,
                    modelName = modelManager.modelInfo(modelManager.managedModel.value).displayName,
                    modelSizeMb = modelManager.modelInfo(modelManager.managedModel.value).approximateSizeMb,
                ),
            )
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

            viewModelScope.launch {
                transcriptionRoutingStore.selectedEngine.collect { engine ->
                    _uiState.update { it.copy(selectedEngine = engine) }
                }
            }

            viewModelScope.launch {
                modelManager.selectedModel.collect { selected ->
                    _uiState.update { it.copy(selectedLocalModel = selected) }
                }
            }

            viewModelScope.launch {
                modelManager.selectedComputeBackend.collect { selected ->
                    _uiState.update { it.copy(selectedComputeBackend = selected) }
                }
            }

            viewModelScope.launch {
                modelManager.managedModel.collect { managed ->
                    val info = modelManager.modelInfo(managed)
                    _uiState.update {
                        it.copy(
                            managedLocalModel = managed,
                            modelName = info.displayName,
                            modelSizeMb = info.approximateSizeMb,
                            downloadedSizeMb = null,
                        )
                    }
                }
            }

            refreshCloudConfiguration()

            modelManager.refreshStatus()
        }

        fun selectEngine(engine: TranscriptionEngine) {
            viewModelScope.launch {
                transcriptionRoutingStore.setSelectedEngine(engine)
            }
        }

        fun manageLocalModel(modelId: LocalSpeechModelId) {
            modelManager.manageModel(modelId)
        }

        fun activateManagedModel() {
            viewModelScope.launch {
                when (val result = modelManager.activateManagedModel()) {
                    LocalSpeechModelActivationResult.Activated ->
                        _uiState.update { it.copy(errorMessage = null) }

                    LocalSpeechModelActivationResult.ModelNotDownloaded ->
                        _uiState.update { it.copy(errorMessage = "Download this model before selecting it") }

                    is LocalSpeechModelActivationResult.Failed ->
                        _uiState.update { it.copy(errorMessage = result.message) }
                }
            }
        }

        fun selectComputeBackend(backend: LocalSpeechComputeBackend) {
            if (_uiState.value.selectedComputeBackend == backend) return
            viewModelScope.launch {
                when (val result = modelManager.activateComputeBackend(backend)) {
                    is LocalSpeechComputeBackendActivationResult.Activated -> {
                        val notice =
                            if (result.usedCpuFallback) {
                                "Vulkan could not start on this device. CPU fallback is active."
                            } else {
                                null
                            }
                        _uiState.update { it.copy(errorMessage = null, computeBackendNotice = notice) }
                    }

                    is LocalSpeechComputeBackendActivationResult.Failed ->
                        _uiState.update { it.copy(errorMessage = result.message) }
                }
            }
        }

        fun managedModelUsesGguf(): Boolean =
            modelManager.modelInfo(_uiState.value.managedLocalModel).backend == LocalSpeechBackend.TRANSCRIBE_GGUF

        fun refreshCloudConfiguration() {
            viewModelScope.launch {
                val status =
                    runCatching { cloudTranscriber.configurationStatus() }
                        .getOrDefault(CloudTranscriptionConfigurationStatus.TEMPORARILY_UNAVAILABLE)
                _uiState.update { it.copy(cloudConfigurationStatus = status) }
            }
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

        /**
         * ERR-22 follow-up (W2 leftover): every AllFilesAccessRequester.openSettings fallback
         * failed — no settings surface exists on this build. Surface manual instructions on
         * the screen's standard error card instead of no-oping, and stop waiting for a grant
         * that can never be given this way.
         */
        fun onStorageSettingsOpenFailed(message: String) {
            savedStateHandle[KEY_AWAITING_ALL_FILES_GRANT] = false
            _uiState.update { it.copy(errorMessage = message) }
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
