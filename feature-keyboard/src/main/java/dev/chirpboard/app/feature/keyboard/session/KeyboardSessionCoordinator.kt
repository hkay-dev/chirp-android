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
import dev.chirpboard.app.feature.keyboard.R
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
import kotlinx.coroutines.runBlocking
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

    /**
     * AUD-02 (keyboard half): the recorder reports sustained digital silence — the platform
     * silenced this client (mic held elsewhere / privacy toggle) while reads keep succeeding.
     * Display-only input to [uiState]'s "no audio detected" hint; gated on [isRecording] at
     * map time and reset on every session start so a session that ends mid-silence can never
     * leak the hint into the next one.
     */
    private val silenceDetected = MutableStateFlow(false)
    private val overlayError = MutableStateFlow<KeyboardOverlayError?>(null)
    private val sensitiveInput = MutableStateFlow(false)
    private val modelBanner = MutableStateFlow(ModelBannerState.Initializing)
    private val modelInitFailedMessage = MutableStateFlow<String?>(null)
    private val llmEnabled = MutableStateFlow(true)
    private val currentMode = MutableStateFlow<ProcessingMode>(ProcessingMode.Proofread)
    private val keyboardDefaultModeId = MutableStateFlow<String?>(null)
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
     * The most recent off-main stop/finalize teardown coroutine (the one that runs
     * [QuickCaptureSessionImpl.stopAsAudioSource] under NonCancellable and then launches the
     * transcription pipeline). [awaitInFlightTeardown] joins it during service destruction so
     * `capture.close()` cannot race the in-flight `stopToFileBacked()` on the shared recorder —
     * either deleting the just-captured temp PCM (data loss) or orphaning it because the
     * transcription pipeline was launched on an already-cancelled scope.
     */
    private var teardownJob: Job? = null

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

    /**
     * Supplies whether the live input session suppresses history persistence (IME-3 incognito).
     * Sampled synchronously at stop time by [stopAndTranscribe] so the suppression follows the
     * field the transcript actually commits into. Set by the IME service.
     */
    var historyPersistenceSuppressed: () -> Boolean = { false }

    private data class PrefsState(
        val modelInitFailedMessage: String?,
        val llmEnabled: Boolean,
        val globalMode: ProcessingMode,
        val keyboardDefaultModeId: String?,
        val overlayError: KeyboardOverlayError?,
    )

    private data class CaptureUiInputs(
        val isRecording: Boolean,
        val phase: InlineTranscriptionPhase,
        val modelBanner: ModelBannerState,
        val silenceDetected: Boolean,
    )

    val uiState: StateFlow<KeyboardUiState> =
        combine(
            combine(isRecording, transcription.phase, modelBanner, silenceDetected) { recording, phase, banner, silenced ->
                CaptureUiInputs(recording, phase, banner, silenced)
            },
            combine(
                modelInitFailedMessage,
                llmEnabled,
                currentMode,
                keyboardDefaultModeId,
                overlayError,
            ) { initFailed, llm, globalMode, defaultModeId, overlay ->
                PrefsState(initFailed, llm, globalMode, defaultModeId, overlay)
            },
            combine(availableModes, sensitiveInput) { modes, sensitive -> modes to sensitive },
        ) { captureState, prefsState, modesAndSensitive ->
            val (modes, sensitive) = modesAndSensitive
            mapKeyboardUiState(
                isRecording = captureState.isRecording,
                transcriptionPhase = captureState.phase,
                modelBanner = captureState.modelBanner,
                silenceDetected = captureState.silenceDetected,
                modelInitFailedMessage = prefsState.modelInitFailedMessage,
                llmEnabled = prefsState.llmEnabled,
                // PLH-1: the keyboard-scoped default mode wins over the global mode when set.
                processingMode =
                    resolveKeyboardSessionMode(
                        keyboardDefaultModeId = prefsState.keyboardDefaultModeId,
                        globalMode = prefsState.globalMode,
                        availableModes = modes,
                    ),
                availableModes = modes,
                overlayError = prefsState.overlayError,
                sensitiveInput = sensitive,
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
                overlayError = null,
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
        // PLH-1: the Keyboard Settings "Default Mode" preference drives the dictation session's
        // processing mode (AI-pill label + the mode sent with every InlineTranscriptionRequest),
        // falling back to the global mode when unset ("Use global setting").
        scope.launch {
            keyboardPreferences.defaultProcessingMode.collect { keyboardDefaultModeId.value = it }
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

        // AUD-02: fires on the recorder's collection coroutine; MutableStateFlow writes are
        // thread-safe, so no main hop is needed. Display-only — never alters the session.
        capture.onSilenceStateChanged = { silenced ->
            silenceDetected.value = silenced
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
     * Blocks the caller until any in-flight off-main stop/finalize teardown finishes. The IME
     * service must call this on the main thread during `onDestroy`, AFTER
     * [cancelRecording] and BEFORE `capture.close()`/`scope.cancel()`.
     *
     * The teardown coroutine ([stopAndTranscribe]/[finalizeActiveRecording]) runs
     * [QuickCaptureSessionImpl.stopAsAudioSource] -> `VoiceRecorder.stopToFileBacked()` on the IO
     * dispatcher under the recorder's `sampleLock`. If `capture.close()` ran on main before that
     * finished, the two would interleave on the lock: `close()` could delete the just-captured
     * temp PCM (data loss), or `finishStopAfterTeardown` would launch the transcription pipeline
     * on the already-cancelled scope (orphaning the staged PCM with no recording row). Joining the
     * job here serializes the whole teardown before destruction proceeds, restoring the
     * pre-PERF-5 invariant that on-main stop and on-main destroy could never interleave.
     *
     * The teardown body is `NonCancellable`, so it always completes; the wait is bounded by the
     * 5-50ms recorder teardown and is only ever paid on service destruction.
     *
     * Joins the [cancelRecording] teardown ([cancelJob]) too: when destruction interrupts an
     * active recording, `cancelRecording(userInitiated = false)` runs `capture.cancelCapture()`
     * off-main on the same recorder, which would otherwise race `capture.close()` on the lock.
     *
     * Crucially, every teardown writer launches its whole body on [teardownDispatcher] (not the
     * scope's Main dispatcher), so the coroutine's resume after the recorder teardown stays on the
     * teardown executor thread and is never posted back to the Android main Handler queue. That is
     * what makes this `runBlocking { join() }` safe to call on the main thread: `runBlocking`
     * installs a private event loop that does NOT pump the Android Looper, so a Main-confined
     * continuation would never run while the main thread is parked here and `join()` would deadlock.
     * Because the joined jobs never need Main to complete, the join always returns.
     */
    fun awaitInFlightTeardown() {
        val pending = listOfNotNull(teardownJob, cancelJob).filterNot { it.isCompleted }
        if (pending.isEmpty()) {
            return
        }
        runBlocking { pending.forEach { it.join() } }
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
                    modelInitFailedMessage.value = context.getString(R.string.keyboard_model_load_failed)
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
        // AUD-02: the recorder only reports silence TRANSITIONS, so a session that ended
        // mid-silence would otherwise leak a stale hint into the next session's first 4s.
        silenceDetected.value = false
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
                            // ERR-8: an IME cannot request runtime permissions itself, so the
                            // overlay routes the user to the app instead of a futile Retry.
                            overlayError.value = KeyboardOverlayError(result.message, showOpenApp = true)
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
        // IME-3: sample the incognito suppression synchronously at stop time so it matches the
        // field the transcript commits into (the commit session is captured at the same moment).
        val suppressHistory = historyPersistenceSuppressed()
        // Flip the UI/cancellation flags synchronously on the caller (IME main) thread so the
        // panel responds to the tap instantly, then hand the actual recorder teardown off-main.
        isRecording.value = false
        capture.abandonAudioFocus()
        HapticFeedback.onRecordStop(context)
        recordingJob?.cancel()
        recordingJob = null

        teardownJob =
            scope.launch(teardownDispatcher) {
                // AudioRecord.stop/release plus the buffered-stream flush take sampleLock across a
                // disk write and a binder transaction (5-50ms). Run them off the main thread. The
                // teardown + state handoff are NonCancellable so a service destroy landing inside
                // this window still stages the captured audio (stopAsAudioSource transfers ownership
                // of the temp PCM away from the recorder) and launches the transcription pipeline.
                // onDestroy joins this job via awaitInFlightTeardown before capture.close()/scope.cancel(),
                // so close() cannot race the in-flight stopToFileBacked() (deleting the temp PCM) and
                // the pipeline is never launched on an already-cancelled scope. That pipeline is an
                // ordinary scope child, so a destroy cancels it unmarked -> it rescues the capture,
                // exactly as before this teardown moved off the main thread. Stopping-timeout/
                // pending-stop ordering intact.
                //
                // The whole body runs on teardownDispatcher (not the scope's Main dispatcher) so the
                // continuation after the recorder teardown never re-dispatches to the Android main
                // Handler queue. awaitInFlightTeardown joins this job with runBlocking on the main
                // thread; if the tail resumed on Main it would deadlock (runBlocking's private event
                // loop does not pump the Looper). All work in the tail (RecordingStateManager,
                // transcription, persistence, AtomicReference) is thread-safe off Main.
                withContext(NonCancellable) {
                    val audioSource = capture.stopAsAudioSource()
                    finishStopAfterTeardown(audioSource, commitText, suppressHistory)
                }
            }
        return true
    }

    private fun finishStopAfterTeardown(
        audioSource: InlineAudioSource?,
        commitText: (String) -> Boolean,
        suppressHistory: Boolean = false,
    ) {
        if (audioSource == null) {
            persistence.discardSamples()
            recordingStateManager.onRecordingCompleted()
            transcription.resetPhase()
            clearPendingStop()
            return
        }

        // IME-3: incognito sessions run against a wrapper that drops COMPLETED/USER_CANCELLED
        // persists (no history) while forwarding RESCUE persists untouched, so the
        // never-drop-captured-speech guarantee is unchanged.
        val sessionPersistence =
            if (suppressHistory) IncognitoCapturePersistence(persistence) else persistence

        sessionPersistence.prepareAudioSource(audioSource)

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
                                processingModeId = sessionProcessingMode().id,
                            ),
                        persistence = sessionPersistence,
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

    /** PLH-1: the mode an inline dictation actually runs with (keyboard default over global). */
    private fun sessionProcessingMode(): ProcessingMode =
        resolveKeyboardSessionMode(
            keyboardDefaultModeId = keyboardDefaultModeId.value,
            globalMode = currentMode.value,
            availableModes = availableModes.value,
        )

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
                // The whole body runs on teardownDispatcher so awaitInFlightTeardown's main-thread
                // runBlocking join never deadlocks waiting on a Main-confined continuation.
                cancelJob =
                    scope.launch(teardownDispatcher) {
                        capture.cancelCapture()
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
        // Flip the UI flag synchronously like stopAndTranscribe/finalizeActiveRecording do.
        // Without this the panel stayed in the Recording phase after a cancel (stale
        // waveform/silence hint) and startRecording's isRecording guard made the very next
        // start — including restartRecording's — a silent no-op until a mic tap cleared it.
        // Display/UI state only: the capture teardown and discard below are unchanged.
        isRecording.value = false
        capture.abandonAudioFocus()
        HapticFeedback.onRecordStop(context)
        recordingJob?.cancel()
        recordingJob = null
        cancelJob =
            scope.launch(teardownDispatcher) {
                // Recorder teardown (stop/release + temp-file delete) is off-main; the discard
                // and state completion stay ordered after it so a follow-up start cannot observe
                // the recorder mid-teardown. The whole body runs on teardownDispatcher so
                // awaitInFlightTeardown's main-thread runBlocking join never deadlocks waiting on a
                // Main-confined continuation.
                capture.cancelCapture()
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
        teardownJob =
            scope.launch(teardownDispatcher) {
                try {
                    withContext(NonCancellable) {
                        // stopAsAudioSource() runs AudioRecord.stop/release + a buffered-stream flush
                        // under sampleLock; keep it off main like the other teardown paths. onDestroy
                        // joins this job via awaitInFlightTeardown before capture.close() so the rescue
                        // persist completes before the recorder is closed under it. The whole body runs
                        // on teardownDispatcher so awaitInFlightTeardown's main-thread runBlocking join
                        // never deadlocks waiting on a Main-confined continuation.
                        val audioSource = capture.stopAsAudioSource()
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

    /** Mic-permission overlay (ERR-8): offers the open-app affordance instead of a futile Retry. */
    fun setMicPermissionError(message: String) {
        overlayError.value = KeyboardOverlayError(message, showOpenApp = true)
    }

    /** Session-scoped overlay error (e.g. "input field changed"); plain dismiss affordance. */
    fun setSessionError(message: String) {
        overlayError.value = KeyboardOverlayError(message, showOpenApp = false)
    }

    fun clearErrorOverlay() {
        overlayError.value = null
    }

    /** Password/blocked field (IME-4): typing aids stay; the center panel shows the notice. */
    fun setSensitiveInput(sensitive: Boolean) {
        sensitiveInput.value = sensitive
    }

    fun toggleLlm() {
        scope.launch {
            keyboardPreferences.setLlmEnabled(!llmEnabled.value)
        }
    }

    fun changeMode(modeId: String) {
        // PLH-1: the in-keyboard mode picker writes the keyboard-scoped default so a pick on this
        // surface can never silently flip the GLOBAL processing mode (the PLH-8 failure class).
        // "Use global setting" remains available in Keyboard Settings.
        scope.launch {
            keyboardPreferences.setDefaultProcessingMode(modeId)
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
