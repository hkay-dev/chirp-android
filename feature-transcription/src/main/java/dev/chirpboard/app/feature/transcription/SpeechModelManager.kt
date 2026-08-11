package dev.chirpboard.app.feature.transcription

import android.util.Log
import androidx.annotation.VisibleForTesting
import dev.chirpboard.app.core.modelreadiness.ModelReadinessEvaluation
import dev.chirpboard.app.core.modelreadiness.ModelReadinessUnavailableReason
import dev.chirpboard.app.core.modelreadiness.SpeechModelDownloadGateway
import dev.chirpboard.app.core.modelreadiness.SpeechModelDownloadWork
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.modelreadiness.SpeechModelStore
import dev.chirpboard.app.core.transcription.LocalSpeechBackend
import dev.chirpboard.app.core.transcription.LocalSpeechComputeBackend
import dev.chirpboard.app.core.transcription.LocalSpeechComputeBackendActivationResult
import dev.chirpboard.app.core.transcription.LocalSpeechModelActivationResult
import dev.chirpboard.app.core.transcription.LocalSpeechModelActivator
import dev.chirpboard.app.core.transcription.LocalSpeechModelId
import dev.chirpboard.app.core.transcription.LocalSpeechModelInfo
import dev.chirpboard.app.core.transcription.LocalSpeechModelSelectionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings-facing adapter over the shared [SpeechModelStore]. (Formerly WhisperModelManager;
 * the app ships Parakeet models, never Whisper.)
 *
 * The model download itself is app-scoped WorkManager unique work behind
 * [SpeechModelDownloadGateway] (ERR-1): this manager only mirrors that work into
 * [modelStatus] in its OWN scope, so the status can never go stale when a collecting UI
 * scope dies mid-download (ERR-26) and leaving the settings screen never cancels the
 * transfer.
 */
@Singleton
class SpeechModelManager
    internal constructor(
        private val speechModelStore: SpeechModelStore,
        private val readinessGate: SpeechModelReadinessGate,
        private val downloadGateway: SpeechModelDownloadGateway,
        private val scope: CoroutineScope,
        private val selectionStore: LocalSpeechModelSelectionStore? = null,
        private val modelActivator: LocalSpeechModelActivator? = null,
    ) {
        @Inject
        constructor(
            speechModelStore: SpeechModelStore,
            readinessGate: SpeechModelReadinessGate,
            downloadGateway: SpeechModelDownloadGateway,
            selectionStore: LocalSpeechModelSelectionStore,
            modelActivator: LocalSpeechModelActivator,
        ) : this(
            speechModelStore = speechModelStore,
            readinessGate = readinessGate,
            downloadGateway = downloadGateway,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            selectionStore = selectionStore,
            modelActivator = modelActivator,
        )

        companion object {
            private const val TAG = "SpeechModelManager"
            const val MODEL_DISPLAY_NAME = SpeechModelStore.DISPLAY_NAME
            const val MODEL_SIZE_MB = SpeechModelStore.APPROXIMATE_SIZE_MB
        }

        sealed interface ModelStatus {
            data object NotDownloaded : ModelStatus

            data object Ready : ModelStatus

            data class Downloading(
                val progress: Float,
                val file: String = "",
            ) : ModelStatus

            /** Download work is scheduled but parked: no network, or retry backoff. */
            data object WaitingForNetwork : ModelStatus

            data class Error(
                val message: String,
            ) : ModelStatus
        }

        private val _modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.NotDownloaded)
        val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

        private val _downloadProgress = MutableStateFlow(0f)
        val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

        val availableModels: List<LocalSpeechModelInfo> =
            selectionStore?.availableModels
                ?: listOf(
                    LocalSpeechModelInfo(
                        id = LocalSpeechModelId.DEFAULT,
                        displayName = MODEL_DISPLAY_NAME,
                        shortDescription = "Reliable compact offline transcription",
                        backend = LocalSpeechBackend.TRANSCRIBE_GGUF,
                        approximateSizeMb = MODEL_SIZE_MB,
                        englishOnly = true,
                        supportsStreamingPreview = false,
                        supportsWordTimings = false,
                    ),
                )

        private val _managedModel =
            MutableStateFlow(selectionStore?.selectedModel?.value ?: LocalSpeechModelId.DEFAULT)
        val managedModel: StateFlow<LocalSpeechModelId> = _managedModel.asStateFlow()
        val selectedModel: StateFlow<LocalSpeechModelId> =
            selectionStore?.selectedModel ?: MutableStateFlow(LocalSpeechModelId.DEFAULT)
        val selectedComputeBackend: StateFlow<LocalSpeechComputeBackend> =
            selectionStore?.selectedComputeBackend ?: MutableStateFlow(LocalSpeechComputeBackend.CPU)

        init {
            scope.launch {
                var previous: SpeechModelDownloadWork = SpeechModelDownloadWork.Idle
                downloadGateway.work.collect { work ->
                    applyDownloadWork(work, previous)
                    previous = work
                }
            }
            refreshStatus()
        }

        /** Start (or keep) the app-scoped download work; status updates arrive via [modelStatus]. */
        fun requestDownload(preferInternalStorage: Boolean = false) {
            // The unique work name is shared by all models; KEEP would silently drop this
            // request while another model's transfer is pending, leaving an optimistic
            // "Downloading" that never becomes real. Refuse honestly instead.
            val active = downloadGateway.work.value
            if ((active is SpeechModelDownloadWork.Waiting || active is SpeechModelDownloadWork.Running) &&
                !workAppliesToManagedModel(active)
            ) {
                _modelStatus.value =
                    ModelStatus.Error("Another model is still downloading. Wait for it to finish or cancel it first.")
                return
            }
            // Optimistic flip so the UI reacts before WorkManager reports RUNNING.
            if (_modelStatus.value !is ModelStatus.Ready) {
                _modelStatus.value = ModelStatus.Downloading(0f)
                _downloadProgress.value = 0f
            }
            if (selectionStore == null) {
                downloadGateway.startDownload(preferInternalStorage)
            } else {
                downloadGateway.startDownload(_managedModel.value, preferInternalStorage)
            }
        }

        /** Cancel scheduled/running download work; partial files are kept for a later resume. */
        fun cancelDownload() {
            downloadGateway.cancelDownload()
        }

        fun refreshStatus() {
            scope.launch {
                try {
                    applyEvaluation(evaluateManagedModel())
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // This scope lives in the IME-shared process; an evaluation failure
                    // (e.g. storage access revoked) must degrade, never crash the process.
                    // The raw exception stays in the log only (I18N-05).
                    Log.e(TAG, "Model readiness evaluation failed", e)
                    _modelStatus.value = ModelStatus.Error("Couldn't check the speech model")
                }
            }
        }

        suspend fun isModelDownloaded(): Boolean = evaluateManagedModel().isReady

        suspend fun getDownloadedSize(): Long =
            if (selectionStore == null) speechModelStore.getDownloadedSize()
            else speechModelStore.getDownloadedSize(_managedModel.value)

        fun manageModel(modelId: LocalSpeechModelId) {
            // Switching mid-download is safe now that download work carries its model id:
            // the other model's progress/errors are filtered out by workAppliesToManagedModel,
            // and switching back re-derives the live download state. Blocking the switch here
            // used to trap the user on a card stuck at "Waiting for network".
            _managedModel.value = modelId
            refreshStatus()
        }

        fun modelInfo(modelId: LocalSpeechModelId = _managedModel.value): LocalSpeechModelInfo =
            availableModels.first { it.id == modelId }

        suspend fun activateManagedModel(): LocalSpeechModelActivationResult {
            val result =
                modelActivator?.activate(_managedModel.value)
                    ?: LocalSpeechModelActivationResult.Failed("Model switching is unavailable")
            if (result is LocalSpeechModelActivationResult.Activated) {
                readinessGate.invalidate()
                readinessGate.verifyIfNeeded()
            }
            return result
        }

        suspend fun activateComputeBackend(
            backend: LocalSpeechComputeBackend,
        ): LocalSpeechComputeBackendActivationResult {
            return modelActivator?.activateComputeBackend(backend)
                ?: LocalSpeechComputeBackendActivationResult.Failed("Compute switching is unavailable")
        }

        suspend fun isComputeBackendAvailable(backend: LocalSpeechComputeBackend): Boolean =
            modelActivator?.isComputeBackendAvailable(backend) ?: (backend == LocalSpeechComputeBackend.CPU)

        suspend fun deleteModel(): Boolean =
            withContext(Dispatchers.IO) {
                if (modelActivator?.releaseForDeletion(_managedModel.value) == false) {
                    _modelStatus.value = ModelStatus.Error("This model is transcribing right now. Try again shortly.")
                    return@withContext false
                }
                val success =
                    if (selectionStore == null) speechModelStore.deleteModel()
                    else speechModelStore.deleteModel(_managedModel.value)
                if (success) {
                    // The store's deleteModel already scrubbed its verification cache for the
                    // deleted model; a second blanket invalidation here would needlessly force
                    // every other model's next readiness check into a full re-hash.
                    readinessGate.invalidate()
                    applyEvaluation(evaluateManagedModel())
                }
                success
            }

        /**
         * True when the download work targets the currently managed model. Work without a
         * recorded id (enqueued before the tag existed) is assumed to be ours so an
         * in-flight download across an app update keeps reporting progress. This is what
         * keeps model A's running transfer, byte counts, and failure message off model B's
         * card when the user switches which model they are managing.
         */
        private fun workAppliesToManagedModel(work: SpeechModelDownloadWork): Boolean =
            work.modelId == null || work.modelId == _managedModel.value

        @VisibleForTesting
        internal suspend fun applyDownloadWork(
            work: SpeechModelDownloadWork,
            previous: SpeechModelDownloadWork,
        ) {
            if (!workAppliesToManagedModel(work)) {
                return
            }
            when (work) {
                SpeechModelDownloadWork.Idle -> {
                    // CANCELLED (or pruned) work: re-derive the status from disk so a
                    // cancelled download honestly shows NotDownloaded again.
                    if (previous != SpeechModelDownloadWork.Idle) {
                        refreshFromStore()
                    }
                }

                is SpeechModelDownloadWork.Waiting -> {
                    if (_modelStatus.value !is ModelStatus.Ready) {
                        _modelStatus.value = ModelStatus.WaitingForNetwork
                    }
                }

                is SpeechModelDownloadWork.Running -> {
                    _downloadProgress.value = work.progress
                    if (_modelStatus.value !is ModelStatus.Ready) {
                        _modelStatus.update { ModelStatus.Downloading(work.progress, work.file) }
                    }
                }

                SpeechModelDownloadWork.Succeeded -> {
                    _downloadProgress.value = 0f
                    // The worker already invalidated + verified the readiness gate; checking
                    // through the store here is idempotent and also covers the stale
                    // SUCCEEDED emission WorkManager replays after a process restart.
                    refreshFromStore()
                }

                is SpeechModelDownloadWork.Failed -> {
                    _downloadProgress.value = 0f
                    if (_modelStatus.value !is ModelStatus.Ready) {
                        _modelStatus.update { ModelStatus.Error(work.message) }
                    }
                }
            }
        }

        private suspend fun refreshFromStore() {
            try {
                applyEvaluation(evaluateManagedModel())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Model readiness evaluation failed", e)
                _modelStatus.value = ModelStatus.Error("Couldn't check the speech model")
            }
        }

        private fun applyEvaluation(evaluation: ModelReadinessEvaluation) {
            val work =
                downloadGateway.work.value.takeIf(::workAppliesToManagedModel)
                    ?: SpeechModelDownloadWork.Idle
            _modelStatus.value = statusFor(evaluation, work)
        }

        private suspend fun evaluateManagedModel(): ModelReadinessEvaluation =
            if (selectionStore == null) speechModelStore.evaluateReadiness()
            else speechModelStore.evaluateReadiness(_managedModel.value)

        /**
         * Derives the surfaced status from the on-disk evaluation and the live download
         * work. Active/failed download work outranks a bare "not downloaded" so an
         * interrupted download is never silently reset to "Not downloaded" (ERR-1), while a
         * ready model always wins over stale terminal work.
         */
        @VisibleForTesting
        internal fun statusFor(
            evaluation: ModelReadinessEvaluation,
            work: SpeechModelDownloadWork,
        ): ModelStatus =
            when {
                evaluation.isReady -> ModelStatus.Ready
                work is SpeechModelDownloadWork.Running -> ModelStatus.Downloading(work.progress, work.file)
                work is SpeechModelDownloadWork.Waiting -> ModelStatus.WaitingForNetwork
                work is SpeechModelDownloadWork.Failed -> ModelStatus.Error(work.message)
                evaluation.unavailableReason == ModelReadinessUnavailableReason.INTEGRITY_MISMATCH ->
                    ModelStatus.Error("Model integrity check failed")

                else -> ModelStatus.NotDownloaded
            }
    }
