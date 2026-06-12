package dev.chirpboard.app.feature.keyboard.session

import android.content.Context
import android.util.Log
import dev.chirpboard.app.core.llm.ProcessingMode
import dev.chirpboard.app.core.llm.ProcessingModeListItem
import dev.chirpboard.app.core.llm.ProcessingModePort
import dev.chirpboard.app.core.modelreadiness.ModelReadinessState
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.modelreadiness.VerificationTrigger
import dev.chirpboard.app.core.preferences.KeyboardPreferences
import dev.chirpboard.app.core.quickcapture.QuickCaptureStartResult
import dev.chirpboard.app.core.recording.KeyboardPendingStopStore
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.transcription.InlineAudioSource
import dev.chirpboard.app.core.transcription.InlineCapturePersistReason
import dev.chirpboard.app.core.transcription.InlineCapturePersistence
import dev.chirpboard.app.core.transcription.InlineTranscriptionPhase
import dev.chirpboard.app.core.transcription.InlineTranscriptionPort
import dev.chirpboard.app.core.transcription.InlineTranscriptionRequest
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.feature.keyboard.haptic.HapticFeedback
import dev.chirpboard.app.feature.keyboard.quickcapture.QuickCaptureSessionImpl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

class KeyboardSessionCoordinator(
    private val tag: String,
    private val context: Context,
    private val scope: CoroutineScope,
    val capture: QuickCaptureSessionImpl,
    private val transcription: InlineTranscriptionPort,
    private val persistence: InlineCapturePersistence,
    private val transcriberProvider: TranscriberProvider,
    private val recordingStateManager: RecordingStateManager,
    private val keyboardPreferences: KeyboardPreferences,
    private val modePort: ProcessingModePort,
    private val pendingStopStore: KeyboardPendingStopStore,
    private val modelReadinessGate: SpeechModelReadinessGate,
    private val teardownDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val isRecording = MutableStateFlow(false)
    private val permissionError = MutableStateFlow<String?>(null)
    private val modelBanner = MutableStateFlow(ModelBannerState.Initializing)
    private val modelInitFailedMessage = MutableStateFlow<String?>(null)
    private val llmEnabled = MutableStateFlow(true)
    private val currentMode = MutableStateFlow<ProcessingMode>(ProcessingMode.Proofread)
    private val availableModes = MutableStateFlow<List<ProcessingModeListItem>>(emptyList())

    private var recordingJob: Job? = null
    private var stopRequestedDuringStart = false
    private var startJob: Job? = null
    private var transcriptionJob: Job? = null
    private var modelInitJob: Job? = null

    /**
     * The most recent cancel teardown coroutine. [restartRecording] joins it before starting a
     * new session so the deferred (off-main) recorder teardown of the cancelled session can never
     * race the next [capture] start on the shared recorder.
     */
    private var cancelJob: Job? = null

    /**
     * Identifies the stop pipeline currently allowed to drive the recording state machine.
     * A pipeline detached by the stopping-timeout rescue keeps transcribing in the
     * background but must no longer touch the (already recovered) state machine or the
     * pending-stop store. Held in an [AtomicReference] so the pipeline callbacks (which
     * run on Default) and the rescue/cancel paths (Main) claim it with a single atomic
     * compare-and-set instead of a racy check-then-act.
     */
    private val activeStopToken = AtomicReference<Any?>(null)

    /**
     * Supplies a commit callback bound to the live input session. Set by the IME service so
     * stops it does not initiate directly (for example the max-duration limit) still commit
     * recognized text to the field exactly like a user-initiated stop.
     */
    var commitTextProvider: () -> ((String) -> Boolean)? = { null }

    private data class PrefsState(
        val modelInitFailedMessage: String?,
        val llmEnabled: Boolean,
        val processingMode: ProcessingMode,
        val permissionError: String?,
    )

    val uiState: StateFlow<KeyboardUiState> =
        combine(
            combine(isRecording, transcription.phase, modelBanner) { recording, phase, banner ->
                Triple(recording, phase, banner)
            },
            combine(modelInitFailedMessage, llmEnabled, currentMode, permissionError) { initFailed, llm, mode, permError ->
                PrefsState(initFailed, llm, mode, permError)
            },
            availableModes,
        ) { captureState, prefsState, modes ->
            val (recording, phase, banner) = captureState
            mapKeyboardUiState(
                isRecording = recording,
                transcriptionPhase = phase,
                modelBanner = banner,
                modelInitFailedMessage = prefsState.modelInitFailedMessage,
                llmEnabled = prefsState.llmEnabled,
                processingMode = prefsState.processingMode,
                availableModes = modes,
                permissionError = prefsState.permissionError,
            )
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            mapKeyboardUiState(
                isRecording = false,
                transcriptionPhase = InlineTranscriptionPhase.Idle,
                modelBanner = ModelBannerState.Initializing,
                modelInitFailedMessage = null,
                llmEnabled = true,
                processingMode = ProcessingMode.Proofread,
                availableModes = emptyList(),
                permissionError = null,
            ),
        )

    private val stoppingTimeoutRescue: suspend (RecordingState.Stopping) -> Unit = {
        rescueStoppingTimeout()
    }

    init {
        recordingStateManager.setStoppingTimeoutHandler(RecordingOrigin.KEYBOARD, stoppingTimeoutRescue)

        scope.launch {
            keyboardPreferences.llmEnabled.collect { llmEnabled.value = it }
        }
        scope.launch {
            keyboardPreferences.microphoneGain.collect { capture.gainMultiplier = it }
        }
        scope.launch {
            modePort.currentMode.collect { currentMode.value = it }
        }
        scope.launch {
            modePort.selectableModes.collect { availableModes.value = it }
        }
        // Drive the banner from the readiness gate's cached, IO-verified StateFlow instead
        // of stat-ing (worst case SHA-256 hashing) the 652MB model on the IME main thread.
        // The gate verifies off-main and caches the result; the keyboard only ever reads it.
        scope.launch {
            modelReadinessGate.state.collect { recomputeModelBanner() }
        }

        capture.onRecordingError = { error ->
            scope.launch {
                if (!isRecording.value) {
                    // A concurrent stop already tore the capture down; a late capture error
                    // must not clobber the stop pipeline or in-flight transcription.
                    Log.w(tag, "Ignoring capture error after recording stopped: ${error.userMessage}")
                } else {
                    isRecording.value = false
                    recordingJob?.cancel()
                    recordingJob = null
                    capture.abandonAudioFocus()
                    recordingStateManager.onRecordingError(error.userMessage)
                    transcription.setError(error.userMessage)
                    // The session ended here; drop any stop queued against it so it
                    // cannot fire on the next healthy recording.
                    clearPendingStop()
                }
            }
        }

        capture.onLimitReached = {
            scope.launch {
                if (isRecording.value) {
                    // Commit limit-triggered stops through the live input session exactly like
                    // a user-initiated stop. With no input session available the commit reports
                    // failure, which routes the transcript into the rescue persistence path.
                    val commitText = commitTextProvider() ?: { _: String -> false }
                    stopAndTranscribe(commitText)
                }
            }
        }
    }

    /**
     * Releases callbacks this coordinator registered on shared singletons.
     * Clears the KEYBOARD stopping-timeout handler only when it is still ours.
     */
    fun destroy() {
        recordingStateManager.clearStoppingTimeoutHandler(RecordingOrigin.KEYBOARD, stoppingTimeoutRescue)
    }

    /**
     * The stopping budget elapsed while the stop pipeline was still working. Never destroy
     * in-flight transcription here: detach it to finish in the background, make sure the
     * captured audio survives, and move the state machine out of STOPPING so the keyboard
     * recovers.
     */
    private suspend fun rescueStoppingTimeout() {
        withContext(Dispatchers.Main) {
            // Claim the token atomically: a pipeline callback racing on another thread
            // either wins the claim (and drives the state machine itself) or loses it,
            // never both, so the rescue cannot double-drive an already recovered machine.
            val claimedToken = activeStopToken.getAndSet(null)
            if (transcriptionJob?.isActive == true) {
                Log.w(tag, "Stopping timed out with transcription in flight; continuing in background")
                // Fully detach the in-flight pipeline: it persists or discards its own
                // explicit audio source on every terminal path, so the next stop must not
                // cancel it (transcriptionJob.cancel) or delete its temp PCM via
                // prepareAudioSource -> discardSamples on the still-staged source.
                transcriptionJob = null
                persistence.releasePendingAudioSource()
                if (claimedToken != null) {
                    recordingStateManager.onRecordingCompleted()
                }
                transcription.setError(STOP_TIMEOUT_IN_PROGRESS_MESSAGE)
            } else {
                withContext(NonCancellable) {
                    // The rescue runs inside RecordingStateManager's handler-less scope:
                    // a persistence failure must never escape, or the state machine stays
                    // stuck in Stopping and the unhandled exception kills the IME process.
                    runCatching {
                        persistence.persistAudioSource(
                            audioSource = null,
                            rawText = null,
                            processedText = null,
                            errorMessage = STOP_TIMEOUT_RESCUE_MESSAGE,
                            reason = InlineCapturePersistReason.RESCUE,
                        )
                    }.onFailure { Log.e(tag, "Failed to persist stop-timeout rescue entry", it) }
                }
                recordingStateManager.onRecordingCompleted()
                transcription.setError(STOP_TIMEOUT_RESCUE_MESSAGE)
            }
            clearPendingStop()
        }
    }

    /**
     * Called on the latency-critical IME-show path (onCreate/onStartInputView). Must never
     * touch the filesystem: it reads the readiness gate's last-known cached state and the
     * in-memory recognizer/init-job flags, then kicks a background (IO-dispatched) gate
     * refresh so the cached value catches up without blocking the keyboard from appearing.
     */
    fun refreshModelStatus() {
        recomputeModelBanner()
        modelReadinessGate.warmupIfNeeded(VerificationTrigger.KEYBOARD_DICTATION)
    }

    /**
     * Recomputes the banner from cached, in-memory state only (no disk I/O). The recognizer's
     * in-memory [TranscriberProvider.isReady] / [modelInitJob] flags take precedence so an
     * actively loading or already-loaded model never flickers back to a download/error banner
     * from a stale gate emission; otherwise the gate's cached readiness decides.
     */
    private fun recomputeModelBanner() {
        modelBanner.value =
            when {
                transcriberProvider.isReady() -> ModelBannerState.None
                modelInitJob?.isActive == true -> ModelBannerState.Initializing
                modelInitFailedMessage.value != null -> ModelBannerState.InitFailed
                else ->
                    when (modelReadinessGate.state.value) {
                        // Model files are verified present but the recognizer is not loaded into
                        // memory yet: surface the same "initializing" banner as the legacy check.
                        is ModelReadinessState.Ready -> ModelBannerState.Initializing
                        // Missing/corrupt files: the user must (re)download the model.
                        is ModelReadinessState.Unavailable -> ModelBannerState.NotDownloaded
                        // Unknown / mid-verification / transient verification error: keep the
                        // neutral initializing banner (the background warmup retries) rather than
                        // flashing a hard failure, matching the legacy fallthrough behavior.
                        ModelReadinessState.Unknown,
                        is ModelReadinessState.Checking,
                        is ModelReadinessState.Error,
                        -> ModelBannerState.Initializing
                    }
            }
    }

    fun initializeModel() {
        if (transcriberProvider.isReady()) {
            recomputeModelBanner()
            return
        }
        if (modelInitJob?.isActive == true) {
            return
        }

        modelInitJob =
            scope.launch {
                recomputeModelBanner()
                // isModelDownloaded() stats the model files; keep it off the IME main thread.
                val downloaded = withContext(teardownDispatcher) { transcriberProvider.isModelDownloaded() }
                if (!downloaded) {
                    recomputeModelBanner()
                    return@launch
                }
                val initialized =
                    withContext(Dispatchers.Default) {
                        transcriberProvider.initialize()
                    }
                if (initialized) {
                    Log.d(tag, "Recognizer ready")
                    modelInitFailedMessage.value = null
                    recomputeModelBanner()
                } else {
                    Log.e(tag, "Failed to initialize recognizer")
                    modelInitFailedMessage.value = "Failed to load model"
                    modelBanner.value = ModelBannerState.InitFailed
                }
            }
    }

    fun onMicTap(commitText: (String) -> Boolean) {
        val panel = uiState.value.voicePanel
        when {
            isRecording.value -> stopAndTranscribe(commitText)
            startJob?.isActive == true -> requestStopDuringStart()
            panel == VoicePanelPhase.Error -> {
                transcription.resetPhase()
                initializeModel()
            }
            panel == VoicePanelPhase.LlmError -> transcription.resetPhase()
            else -> startRecording()
        }
    }

    fun startRecording() {
        if (isRecording.value || startJob?.isActive == true) {
            return
        }
        stopRequestedDuringStart = false
        startJob =
            scope.launch {
                try {
                    when (val result = capture.start()) {
                        is QuickCaptureStartResult.Success -> {
                            if (stopRequestedDuringStart) {
                                capture.abandonAudioFocus()
                                // Recorder teardown (stop/release + temp-file delete) off the
                                // IME main thread, like the stop and cancel paths.
                                withContext(teardownDispatcher) { capture.cancelCapture() }
                                recordingStateManager.onRecordingCompleted()
                                transcription.resetPhase()
                                // This session is over; a stop enqueued during the
                                // Starting window must not fire on a later session.
                                clearPendingStop()
                                return@launch
                            }
                            HapticFeedback.onRecordStart(context)
                            isRecording.value = true
                            recordingJob = scope.launch { capture.collectSamples() }
                        }

                        is QuickCaptureStartResult.PermissionDenied -> {
                            permissionError.value = result.message
                        }

                        is QuickCaptureStartResult.AudioFocusDenied -> {
                            transcription.setError(result.message)
                        }

                        is QuickCaptureStartResult.Failed -> {
                            transcription.setError(result.message)
                        }

                        is QuickCaptureStartResult.AlreadyRecording -> Unit
                    }
                } finally {
                    stopRequestedDuringStart = false
                    startJob = null
                }
            }
    }

    private fun requestStopDuringStart(): Boolean {
        if (startJob?.isActive != true) {
            return false
        }
        stopRequestedDuringStart = true
        return true
    }

    fun stopAndTranscribe(commitText: (String) -> Boolean): Boolean {
        if (!isRecording.value) {
            return requestStopDuringStart()
        }
        // Flip the UI/cancellation flags synchronously on the caller (IME main) thread so the
        // panel responds to the tap instantly, then hand the actual recorder teardown off-main.
        isRecording.value = false
        capture.abandonAudioFocus()
        HapticFeedback.onRecordStop(context)
        recordingJob?.cancel()
        recordingJob = null

        scope.launch {
            // AudioRecord.stop/release plus the buffered-stream flush take sampleLock across a
            // disk write and a binder transaction (5-50ms). Run them off the main thread. The
            // teardown + state handoff are NonCancellable so a service destroy landing inside
            // this window still stages the captured audio (stopAsAudioSource transfers ownership
            // of the temp PCM away from the recorder, so capture.close cannot delete it) and
            // launches the transcription pipeline. That pipeline is an ordinary scope child, so
            // a destroy cancels it unmarked -> it rescues the capture, exactly as before this
            // teardown moved off the main thread. Stopping-timeout/pending-stop ordering intact.
            withContext(NonCancellable) {
                val audioSource = withContext(teardownDispatcher) { capture.stopAsAudioSource() }
                finishStopAfterTeardown(audioSource, commitText)
            }
        }
        return true
    }

    private fun finishStopAfterTeardown(
        audioSource: InlineAudioSource?,
        commitText: (String) -> Boolean,
    ) {
        if (audioSource == null) {
            persistence.discardSamples()
            recordingStateManager.onRecordingCompleted()
            transcription.resetPhase()
            clearPendingStop()
            return
        }

        persistence.prepareAudioSource(audioSource)

        recordingStateManager.transitionToStopping()
        recordingStateManager.startStoppingTimeout(fileSizeBytes = audioSource.sizeInBytes())

        transcriptionJob?.cancel()
        val stopToken = Any()
        activeStopToken.set(stopToken)
        var newJob: Job? = null
        newJob =
            scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                try {
                    transcription.transcribeWithCommitResult(
                        request =
                            InlineTranscriptionRequest(
                                audioSource = audioSource,
                                llmEnabled = llmEnabled.value,
                                processingModeId = currentMode.value.id,
                            ),
                        persistence = persistence,
                        commitText = commitText,
                        onRecordingCompleted = { onStopPipelineCompleted(stopToken) },
                        onRecordingError = { message -> onStopPipelineError(stopToken, message) },
                    )
                } finally {
                    if (transcriptionJob === newJob) {
                        transcriptionJob = null
                    }
                }
            }
        transcriptionJob = newJob
        checkNotNull(newJob).start()
    }

    private fun onStopPipelineCompleted(stopToken: Any) {
        if (activeStopToken.compareAndSet(stopToken, null)) {
            recordingStateManager.onRecordingCompleted()
            // Token-gated like the state-machine call: a pipeline detached by the
            // stopping-timeout rescue finishing late must not wipe a pending stop
            // that was enqueued for a newer session.
            clearPendingStop()
        }
    }

    private fun onStopPipelineError(
        stopToken: Any,
        message: String,
    ) {
        if (activeStopToken.compareAndSet(stopToken, null)) {
            recordingStateManager.onRecordingError(message)
            clearPendingStop()
        }
    }

    private fun clearPendingStop() {
        scope.launch {
            runCatching { pendingStopStore.clear() }
                .onFailure { Log.w(tag, "Failed to clear pending keyboard stop", it) }
        }
    }

    private fun InlineAudioSource.sizeInBytes(): Long =
        when (this) {
            is InlineAudioSource.InMemory -> samples.size.toLong() * Float.SIZE_BYTES
            is InlineAudioSource.PcmFloatFile -> sampleCount * Float.SIZE_BYTES
        }

    fun cancelRecording() {
        cancelRecording(userInitiated = true)
    }

    /**
     * [userInitiated] distinguishes an explicit user cancel (cancel tap, restart) from
     * lifecycle teardown (IME service destruction). Only a user cancel marks the
     * in-flight transcription as user-cancelled — letting the persistence layer respect
     * the save preference — while teardown cancellation leaves the mark unset so the
     * pipeline rescues the captured speech instead of dropping it.
     */
    fun cancelRecording(userInitiated: Boolean) {
        val wasRecording = isRecording.value
        val wasStarting = startJob?.isActive == true
        if (!wasRecording && transcriptionJob?.isActive != true) {
            if (wasStarting) {
                stopRequestedDuringStart = true
                startJob?.cancel()
                capture.abandonAudioFocus()
                // capture.cancelCapture() runs AudioRecord.stop/release + temp-file delete under
                // sampleLock; keep it off the IME main thread. The state completion stays ordered
                // after the teardown so a follow-up start cannot observe the recorder mid-teardown.
                cancelJob =
                    scope.launch {
                        withContext(teardownDispatcher) { capture.cancelCapture() }
                        recordingStateManager.onRecordingCompleted()
                        transcription.resetPhase()
                        clearPendingStop()
                    }
            }
            return
        }
        activeStopToken.set(null)
        if (userInitiated && transcriptionJob?.isActive == true) {
            // Mark before cancelling so the pipeline classifies the cancellation as a
            // user discard instead of force-rescuing the capture.
            transcription.markUserCancelled()
        }
        transcriptionJob?.cancel()
        transcriptionJob = null
        if (!wasRecording) {
            recordingStateManager.onRecordingCompleted()
            transcription.resetPhase()
            clearPendingStop()
            return
        }
        capture.abandonAudioFocus()
        HapticFeedback.onRecordStop(context)
        recordingJob?.cancel()
        recordingJob = null
        cancelJob =
            scope.launch {
                // Recorder teardown (stop/release + temp-file delete) is off-main; the discard
                // and state completion stay ordered after it so a follow-up start cannot observe
                // the recorder mid-teardown.
                withContext(teardownDispatcher) { capture.cancelCapture() }
                persistence.discardSamples()
                recordingStateManager.onRecordingCompleted()
                transcription.resetPhase()
                clearPendingStop()
            }
    }

    fun restartRecording() {
        cancelRecording()
        val pendingCancel = cancelJob
        scope.launch {
            // Wait out the cancelled session's off-main recorder teardown before starting the
            // next one; both share a single recorder instance inside [capture].
            pendingCancel?.join()
            startRecording()
        }
    }

    fun finalizeActiveRecording(
        errorMessage: String,
        onComplete: () -> Unit = {},
    ) {
        if (!isRecording.value) {
            return
        }
        capture.abandonAudioFocus()
        recordingJob?.cancel()
        recordingJob = null
        // Flip the UI flag synchronously, then run the recorder teardown off the IME main thread.
        isRecording.value = false
        transcription.resetPhase()
        scope.launch {
            try {
                withContext(NonCancellable) {
                    // stopAsAudioSource() runs AudioRecord.stop/release + a buffered-stream flush
                    // under sampleLock; keep it off main like the other teardown paths.
                    val audioSource = withContext(teardownDispatcher) { capture.stopAsAudioSource() }
                    // Swallow persistence failures: rethrowing out of scope.launch would
                    // crash the IME process after the finally block recovers the state.
                    runCatching {
                        persistence.persistAudioSource(
                            audioSource = audioSource,
                            rawText = null,
                            processedText = null,
                            errorMessage = errorMessage,
                            reason = InlineCapturePersistReason.RESCUE,
                        )
                    }.onFailure { Log.e(tag, "Failed to persist finalized keyboard recording", it) }
                }
            } finally {
                recordingStateManager.onRecordingCompleted()
                clearPendingStop()
                onComplete()
            }
        }
    }

    fun setPermissionError(message: String?) {
        permissionError.value = message
    }

    fun toggleLlm() {
        scope.launch {
            keyboardPreferences.setLlmEnabled(!llmEnabled.value)
        }
    }

    fun changeMode(modeId: String) {
        scope.launch {
            modePort.setModeById(modeId)
        }
    }

    fun isRecordingActive(): Boolean = isRecording.value

    companion object {
        internal const val STOP_TIMEOUT_IN_PROGRESS_MESSAGE =
            "Transcription is taking longer than expected"
        internal const val STOP_TIMEOUT_RESCUE_MESSAGE =
            "Dictation stop timed out; the captured audio was saved to recordings"
    }
}
