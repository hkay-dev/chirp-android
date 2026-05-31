package dev.chirpboard.app.feature.transcription

import dev.chirpboard.app.core.modelreadiness.ModelReadinessEvaluation
import dev.chirpboard.app.core.modelreadiness.ModelReadinessUnavailableReason
import dev.chirpboard.app.core.modelreadiness.SpeechModelDownloadState
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.modelreadiness.SpeechModelStore
import dev.chirpboard.app.core.modelreadiness.VerificationTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings-facing adapter over the shared [SpeechModelStore].
 */
@Singleton
class WhisperModelManager
    @Inject
    constructor(
        private val speechModelStore: SpeechModelStore,
        private val readinessGate: SpeechModelReadinessGate,
    ) {
        companion object {
            const val MODEL_DISPLAY_NAME = SpeechModelStore.DISPLAY_NAME
            const val MODEL_SIZE_MB = SpeechModelStore.APPROXIMATE_SIZE_MB
        }

        sealed interface ModelStatus {
            data object NotDownloaded : ModelStatus

            data object Ready : ModelStatus

            data class Downloading(
                val progress: Float,
            ) : ModelStatus

            data class Error(
                val message: String,
            ) : ModelStatus
        }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val downloadMutex = Mutex()

        private val _modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.NotDownloaded)
        val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

        private val _downloadProgress = MutableStateFlow(0f)
        val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

        init {
            refreshStatus()
        }

        fun refreshStatus() {
            scope.launch {
                applyEvaluation(speechModelStore.evaluateReadiness())
            }
        }

        suspend fun isModelDownloaded(): Boolean = speechModelStore.evaluateReadiness().isReady

        suspend fun getDownloadedSize(): Long = speechModelStore.getDownloadedSize()

        suspend fun deleteModel(): Boolean =
            withContext(Dispatchers.IO) {
                val success = speechModelStore.deleteModel()
                if (success) {
                    speechModelStore.invalidateVerificationCache()
                    readinessGate.invalidate()
                    readinessGate.warmupIfNeeded(VerificationTrigger.APP_STARTUP)
                    applyEvaluation(speechModelStore.evaluateReadiness())
                }
                success
            }

        suspend fun downloadModel(onComplete: suspend () -> Unit = {}) {
            downloadMutex.withLock {
                _modelStatus.value = ModelStatus.Downloading(0f)
                _downloadProgress.value = 0f
                speechModelStore.downloadModel().collect { state ->
                    when (state) {
                        is SpeechModelDownloadState.Progress -> updateDownloadProgress(state.progress)
                        SpeechModelDownloadState.Complete -> {
                            markDownloadComplete()
                            readinessGate.warmupIfNeeded(VerificationTrigger.APP_STARTUP)
                            onComplete()
                        }

                        is SpeechModelDownloadState.Error -> markDownloadError(state.message)
                    }
                }
            }
        }

        fun updateDownloadProgress(progress: Float) {
            _downloadProgress.value = progress
            _modelStatus.update { ModelStatus.Downloading(progress) }
        }

        fun markDownloadComplete() {
            _modelStatus.update { ModelStatus.Ready }
            _downloadProgress.value = 0f
        }

        fun markDownloadError(message: String) {
            _modelStatus.update { ModelStatus.Error(message) }
            _downloadProgress.value = 0f
        }

        private suspend fun applyEvaluation(evaluation: ModelReadinessEvaluation) {
            _modelStatus.value =
                when {
                    evaluation.isReady -> ModelStatus.Ready
                    evaluation.unavailableReason == ModelReadinessUnavailableReason.INTEGRITY_MISMATCH ->
                        ModelStatus.Error("Model integrity check failed")
                    else -> ModelStatus.NotDownloaded
                }
        }
    }
