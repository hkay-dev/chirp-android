package dev.chirpboard.app.feature.keyboard.session

import android.content.Context
import android.util.Log
import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
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
import dev.chirpboard.app.core.transcription.InlineDictationLatencyObserver
import dev.chirpboard.app.core.transcription.InlineTranscriptionPort
import dev.chirpboard.app.core.transcription.InlineTranscriptionRequest
import dev.chirpboard.app.core.transcription.KeyboardDictationHandoff
import dev.chirpboard.app.core.transcription.KeyboardDictationHandoffRequest
import dev.chirpboard.app.core.transcription.KeyboardDictationHandoffResult
import dev.chirpboard.app.core.transcription.KeyboardDictationLiveCapture
import dev.chirpboard.app.core.transcription.KeyboardDictationLiveCaptureRequest
import dev.chirpboard.app.core.transcription.TranscriptionEngine
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.core.transcription.TranscriptionRoutingStore
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.transcription.StreamingTranscriberProvider
import dev.chirpboard.app.feature.keyboard.R
import dev.chirpboard.app.feature.keyboard.haptic.HapticFeedback
import dev.chirpboard.app.feature.keyboard.quickcapture.QuickCaptureSessionImpl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class KeyboardSessionCoordinator(
    private val tag: String,
    private val context: Context,
    private val scope: CoroutineScope,
    val capture: QuickCaptureSessionImpl,
    private val transcription: InlineTranscriptionPort,
    private val persistence: InlineCapturePersistence,
    private val keyboardDictationHandoff: KeyboardDictationHandoff,
    private val transcriptionRoutingStore: TranscriptionRoutingStore,
    private val transcriberProvider: TranscriberProvider,
    private val recordingStateManager: RecordingStateManager,
    private val keyboardPreferences: KeyboardPreferences,
    private val modePort: ProcessingModePort,
    private val pendingStopStore: KeyboardPendingStopStore,
    private val modelReadinessGate: SpeechModelReadinessGate,
    private val teardownDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val streamingTranscriberProvider: StreamingTranscriberProvider? = null,
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

    /**
     * MIC-014 (keyboard half): the session's ACTIVE input device disconnected mid-dictation
     * (hot-unplug, Bluetooth drop). Inform-don't-stop on this surface: the platform reroutes
     * capture to a fallback mic and the dictation continues, so this only drives a transient
     * status hint. Like [silenceDetected] it is gated on the live Recording phase at map time
     * and reset on every session start so it can never leak into the next session.
     */
    private val deviceLost = MutableStateFlow(false)
    private val overlayError = MutableStateFlow<KeyboardOverlayError?>(null)
    private val sensitiveInput = MutableStateFlow(false)
    private val modelBanner = MutableStateFlow(ModelBannerState.Initializing)
    private val modelInitFailedMessage = MutableStateFlow<String?>(null)
    private val selectedEngine = MutableStateFlow<TranscriptionEngine?>(null)
    private val llmEnabled = MutableStateFlow(true)
    private val currentMode = MutableStateFlow<ProcessingMode>(ProcessingMode.Proofread)
    private val keyboardDefaultModeId = MutableStateFlow<String?>(null)
    private val availableModes = MutableStateFlow<List<ProcessingModeListItem>>(emptyList())
    private val livePartialTranscript = MutableStateFlow<String?>(null)

    private var recordingJob: Job? = null
    private var stopRequestedDuringStart = false
    private var startJob: Job? = null
    private var transcriptionJob: Job? = null
    private var modelInitJob: Job? = null
    private var rollingTranscriptionJob: Job? = null
    private var streamingPreviewPrepareJob: Job? = null
    @Volatile private var streamingPreviewReady = false
    private var liveCaptureJournalJob: Job? = null
    private var modelWarmupRequested = false
    private var modelInitializationRequested = false
    private var latencyTrace: DictationLatencyTrace? = null

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

    /** Cloud audio is journaled before AudioRecord starts, so process death leaves a replayable file. */
    private val activeLiveCapture = AtomicReference<KeyboardDictationLiveCapture?>(null)

    /**
     * MIC-017: a user cancel that lands inside the stop-teardown window — [isRecording]
     * already false, [teardownJob] still tearing the recorder down, transcription pipeline
     * not launched yet — has no job to cancel, so [cancelRecording] records the intent here.
     * [finishStopAfterTeardown] consumes it (check-and-clear) to discard the capture through
     * the USER_CANCELLED persistence path and skip the pipeline, instead of committing a
     * dictation the user just cancelled. Set on the IME main thread, consumed on
     * [teardownDispatcher]; cleared on every session start so a stale intent can never
     * discard a later session's stop.
     */
    private val cancelRequestedDuringTeardown = AtomicBoolean(false)

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
        val deviceLost: Boolean,
        val partialTranscript: String?,
    )

    private data class LiveCaptureHints(
        val silenceDetected: Boolean,
        val deviceLost: Boolean,
        val partialTranscript: String?,
    )

    private enum class HandoffDisposition {
        RESOLVED,
        INLINE_LOCAL,
        INLINE_FALLBACK,
    }

    val uiState: StateFlow<KeyboardUiState> =
        combine(
            combine(
                isRecording,
                transcription.phase,
                modelBanner,
                combine(silenceDetected, deviceLost, livePartialTranscript) { silenced, lost, partial ->
                    LiveCaptureHints(silenced, lost, partial)
                },
            ) { recording, phase, banner, hints ->
                CaptureUiInputs(
                    isRecording = recording,
                    phase = phase,
                    modelBanner = banner,
                    silenceDetected = hints.silenceDetected,
                    deviceLost = hints.deviceLost,
                    partialTranscript = hints.partialTranscript,
                )
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
                deviceLost = captureState.deviceLost,
                partialTranscript = captureState.partialTranscript,
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
        scope.launch {
            transcriptionRoutingStore.selectedEngine.collect { engine ->
                selectedEngine.value = engine
                if (engine == TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3) {
                    modelInitJob?.cancel()
                    modelInitJob = null
                    modelBanner.value = ModelBannerState.None
                } else {
                    recomputeModelBanner()
                    if (modelWarmupRequested) {
                        modelReadinessGate.warmupIfNeeded(VerificationTrigger.KEYBOARD_DICTATION)
                    }
                    if (modelInitializationRequested) {
                        initializeLocalModel()
                    }
                }
            }
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
                    val suppressHistory = historyPersistenceSuppressed()
                    isRecording.value = false
                    stopRollingTranscription()
                    recordingJob?.cancel()
                    recordingJob = null
                    capture.abandonAudioFocus()
                    teardownJob =
                        scope.launch(teardownDispatcher) {
                            withContext(NonCancellable) {
                                try {
                                    awaitLiveCaptureJournal()
                                    val audioSource = capture.stopAsAudioSource()
                                    if (audioSource != null) {
                                        // A recorder failure can still leave minutes of valid audio.
                                        // Give it to the same durable handoff as a normal stop. Local
                                        // capture records it as a rescue instead of throwing it away.
                                        finishStopAfterTeardown(
                                            audioSource = audioSource,
                                            commitText = { false },
                                            suppressHistory = suppressHistory,
                                            localRouteRescueMessage = error.userMessage,
                                        )
                                    } else {
                                        // A cloud live-capture marker already owns its durable file.
                                        // Drop only this process's reference so startup recovery can
                                        // inspect the journal rather than deleting its sole source.
                                        activeLiveCapture.getAndSet(null)
                                        clearPendingStop()
                                    }
                                } catch (failure: CancellationException) {
                                    throw failure
                                } catch (failure: Exception) {
                                    Log.e(tag, "Failed to finalize recorder-error audio", failure)
                                    activeLiveCapture.getAndSet(null)
                                    clearPendingStop()
                                }

                                // finishStopAfterTeardown resets the ordinary stop UI. Restore the
                                // recorder's real terminal error once audio ownership is safe.
                                recordingStateManager.onRecordingError(error.userMessage)
                                transcription.setError(error.userMessage)
                            }
                        }
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
        stopRollingTranscription()
        streamingPreviewPrepareJob?.cancel()
        streamingTranscriberProvider?.let { provider ->
            scope.launch(NonCancellable + Dispatchers.IO) { provider.release() }
        }
        recordingStateManager.clearStoppingTimeoutHandler(RecordingOrigin.KEYBOARD, stoppingTimeoutRescue)
    }

    /** Prepares optional preview resources when the IME is visible. It never opens the mic. */
    fun prepareStreamingPreview() {
        if (streamingPreviewReady || streamingPreviewPrepareJob?.isActive == true) return
        val provider = streamingTranscriberProvider ?: return
        streamingPreviewPrepareJob =
            scope.launch(Dispatchers.IO) {
                streamingPreviewReady = runCatching { provider.prepare() }.getOrDefault(false)
                streamingPreviewPrepareJob = null
            }
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
        modelWarmupRequested = true
        if (selectedEngine.value == TranscriptionEngine.LOCAL_PARAKEET) {
            modelReadinessGate.warmupIfNeeded(VerificationTrigger.KEYBOARD_DICTATION)
        }
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
                selectedEngine.value == TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3 -> ModelBannerState.None
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
        modelInitializationRequested = true
        if (selectedEngine.value != TranscriptionEngine.LOCAL_PARAKEET) {
            recomputeModelBanner()
            return
        }
        initializeLocalModel()
    }

    private fun initializeLocalModel() {
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
            // MIC-008: the previous dictation's stop pipeline is still finishing. A start
            // attempt here would only bounce off the still-held global lock and surface a
            // self-referential "mic in use by the keyboard" toast — to the user, "the
            // keyboard" is themselves and they just stopped. The panel already shows the
            // Transcribing phase for this window, so the tap is suppressed with no extra UI.
            stopPipelineInFlight() -> Unit
            else -> startRecording()
        }
    }

    /**
     * True while the previous dictation's stop is still being torn down or transcribed —
     * the window in which the global recording lock is still held by this surface's own
     * just-stopped session (MIC-008): state Stopping with KEYBOARD origin, or a live
     * teardown/transcription job.
     */
    private fun stopPipelineInFlight(): Boolean {
        val state = recordingStateManager.state.value
        return (state is RecordingState.Stopping && state.origin == RecordingOrigin.KEYBOARD) ||
            teardownJob?.isActive == true ||
            transcriptionJob?.isActive == true
    }

    fun startRecording() {
        if (isRecording.value || startJob?.isActive == true) {
            return
        }
        if (selectedEngine.value == TranscriptionEngine.LOCAL_PARAKEET) {
            initializeLocalModel()
        }
        latencyTrace = DictationLatencyTrace(tag).also { it.mark("press") }
        stopRequestedDuringStart = false
        // MIC-017: a cancel intent recorded against a previous session's teardown window
        // must never discard this session's stop.
        cancelRequestedDuringTeardown.set(false)
        // AUD-02: the recorder only reports silence TRANSITIONS, so a session that ended
        // mid-silence would otherwise leak a stale hint into the next session's first 4s.
        silenceDetected.value = false
        // MIC-014: same per-session reset for the device-lost hint.
        deviceLost.value = false
        livePartialTranscript.value = null
        startJob =
            scope.launch {
                val startRequestedAtMs = System.nanoTime() / NANOS_PER_MILLISECOND
                var keepLiveCapture = false
                try {
                    val liveCapture =
                        try {
                            keyboardDictationHandoff.beginLiveCapture(
                                KeyboardDictationLiveCaptureRequest(
                                    llmEnabled = llmEnabled.value,
                                    processingModeId = sessionProcessingMode().id,
                                    suppressHistory = historyPersistenceSuppressed(),
                                    transcriptionEngine = selectedEngine.value,
                                ),
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(tag, "Could not prepare durable keyboard capture", e)
                            transcription.setError(HANDOFF_FAILED_MESSAGE)
                            return@launch
                        }
                    activeLiveCapture.set(liveCapture)
                    when (val result = capture.start(liveCapture?.audioPath)) {
                        is QuickCaptureStartResult.Success -> {
                            latencyTrace?.mark("audio_record_started")
                            if (!capture.awaitFirstSamples()) {
                                capture.abandonAudioFocus()
                                withContext(teardownDispatcher) {
                                    capture.cancelCapture()
                                    abandonActiveLiveCapture()
                                }
                                recordingStateManager.onRecordingError(FIRST_AUDIO_FAILED_MESSAGE)
                                transcription.setError(FIRST_AUDIO_FAILED_MESSAGE)
                                return@launch
                            }
                            latencyTrace?.mark("first_hardware_sample")
                            latencyTrace?.mark("first_durable_sample")
                            Log.i(
                                tag,
                                "Keyboard microphone ready ${System.nanoTime() / NANOS_PER_MILLISECOND - startRequestedAtMs}ms after tap",
                            )
                            keepLiveCapture = true
                            if (stopRequestedDuringStart) {
                                capture.abandonAudioFocus()
                                // Recorder teardown (stop/release + temp-file delete) off the
                                // IME main thread, like the stop and cancel paths.
                                withContext(teardownDispatcher) {
                                    awaitLiveCaptureJournal()
                                    capture.cancelCapture()
                                    abandonActiveLiveCapture()
                                }
                                keepLiveCapture = false
                                recordingStateManager.onRecordingCompleted()
                                transcription.resetPhase()
                                // This session is over; a stop enqueued during the
                                // Starting window must not fire on a later session.
                                clearPendingStop()
                                return@launch
                            }
                            HapticFeedback.onRecordStart(context)
                            isRecording.value = true
                            liveCaptureJournalJob =
                                liveCapture?.let { capturePlan ->
                                    scope.launch(teardownDispatcher) {
                                        runCatching { keyboardDictationHandoff.markLiveCaptureStarted(capturePlan) }
                                            .onFailure { Log.e(tag, "Could not journal live keyboard capture", it) }
                                    }
                                }
                            startRollingTranscription()
                            recordingJob =
                                scope.launch {
                                    // MIC-014: surface a hot-unplug of the session's active
                                    // device as a transient hint (inform, don't stop — only
                                    // RecordingService auto-stops, and only for its own
                                    // capture; the platform reroutes this one to a fallback
                                    // mic). The collector is a child of recordingJob, so
                                    // every stop/cancel/finalize/error path already scopes
                                    // it to the session via recordingJob.cancel().
                                    launch {
                                        capture.deviceLostEvents.collect { deviceLost.value = true }
                                    }
                                }
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
                    if (!keepLiveCapture) {
                        abandonActiveLiveCapture()
                    }
                    stopRequestedDuringStart = false
                    startJob = null
                }
            }
    }

    private suspend fun abandonActiveLiveCapture() {
        val liveCapture = activeLiveCapture.getAndSet(null) ?: return
        withContext(NonCancellable) {
            runCatching { keyboardDictationHandoff.abandonLiveCapture(liveCapture) }
                .onFailure { Log.e(tag, "Failed to discard the live keyboard capture", it) }
        }
    }

    private suspend fun awaitLiveCaptureJournal() {
        val job = liveCaptureJournalJob ?: return
        job.join()
        if (liveCaptureJournalJob === job) {
            liveCaptureJournalJob = null
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
        latencyTrace?.mark("stop_requested")
        stopRollingTranscription()
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
                    awaitLiveCaptureJournal()
                    val audioSource = capture.stopAsAudioSource()
                    latencyTrace?.mark("audio_synced")
                    finishStopAfterTeardown(audioSource, commitText, suppressHistory)
                }
            }
        return true
    }

    /**
     * Produces best-effort live text from overlapping windows of the file that remains the
     * lossless source of truth. A slow or unavailable recognizer can only delay this preview. It
     * cannot block AudioRecord or change the final full-file transcription.
     */
    private fun startRollingTranscription() {
        rollingTranscriptionJob?.cancel()
        if (selectedEngine.value != TranscriptionEngine.LOCAL_PARAKEET) return
        rollingTranscriptionJob =
            scope.launch(Dispatchers.Default) {
                val streamingSession =
                    if (streamingPreviewReady) {
                        streamingTranscriberProvider?.openSession(VoiceRecorder.SAMPLE_RATE)
                    } else {
                        null
                    }
                if (streamingSession != null) {
                    var consumedSamples = 0
                    try {
                        while (isRecording.value) {
                            val snapshot = capture.activeFileBackedSnapshot()
                            if (snapshot != null && snapshot.sampleCount > consumedSamples) {
                                val samples =
                                    withContext(teardownDispatcher) {
                                        readIncrementalPcmSamples(
                                            path = snapshot.file.absolutePath,
                                            startSample = consumedSamples,
                                            availableSamples = snapshot.sampleCount,
                                        )
                                    }
                                if (samples.isNotEmpty()) {
                                    consumedSamples += samples.size
                                    streamingSession.accept(samples).takeIf { it.isNotBlank() }?.let {
                                        livePartialTranscript.value = it
                                    }
                                }
                            }
                            delay(STREAMING_TRANSCRIPTION_POLL_MS)
                        }
                    } finally {
                        runCatching { streamingSession.close() }
                    }
                    return@launch
                }

                // Production always supplies an isolated streaming provider. If its optional
                // model is unavailable, omit preview rather than queueing work on Parakeet's
                // authoritative final recognizer and risking delayed delivery after stop.
                if (streamingTranscriberProvider != null) return@launch

                // Optional first-pass model is unavailable, so retain the existing overlapping
                // file-window preview. The complete PCM file remains authoritative either way.
                delay(LIVE_TRANSCRIPTION_INITIAL_DELAY_MS)
                while (isRecording.value) {
                    if (transcriberProvider.isReady()) {
                        val snapshot = capture.activeFileBackedSnapshot()
                        if (snapshot != null && snapshot.sampleCount >= snapshot.sampleRate * LIVE_TRANSCRIPTION_MIN_SECONDS) {
                            val samples =
                                withContext(teardownDispatcher) {
                                    readRollingPcmWindow(
                                        path = snapshot.file.absolutePath,
                                        availableSamples = snapshot.sampleCount,
                                        sampleRate = snapshot.sampleRate,
                                    )
                                }
                            if (samples.isNotEmpty() && isRecording.value) {
                                when (val outcome = transcriberProvider.transcribe(samples, snapshot.sampleRate)) {
                                    is TranscriptionOutcome.Success -> {
                                        livePartialTranscript.value =
                                            mergeRollingTranscript(livePartialTranscript.value, outcome.text)
                                    }

                                    else -> Unit
                                }
                            }
                        }
                    }
                    delay(LIVE_TRANSCRIPTION_INTERVAL_MS)
                }
            }
    }

    private fun stopRollingTranscription() {
        rollingTranscriptionJob?.cancel()
        rollingTranscriptionJob = null
    }

    private suspend fun finishStopAfterTeardown(
        audioSource: InlineAudioSource?,
        commitText: (String) -> Boolean,
        suppressHistory: Boolean = false,
        localRouteRescueMessage: String? = null,
    ) {
        // MIC-017: consume a cancel that landed inside the teardown window (check-and-clear,
        // so a stale flag can never affect a later stop). The user changed their mind after
        // the stop tap but before the pipeline existed; honor it here — discard the capture
        // through the USER_CANCELLED persistence path (which respects the save preference
        // and the IME-3 incognito wrapper) and never launch the transcription pipeline.
        if (cancelRequestedDuringTeardown.getAndSet(false)) {
            if (audioSource == null) {
                abandonActiveLiveCapture()
                persistence.discardSamples()
            } else {
                if (activeLiveCapture.get() != null) {
                    abandonActiveLiveCapture()
                } else {
                    val sessionPersistence =
                        if (suppressHistory) IncognitoCapturePersistence(persistence) else persistence
                    // Swallow persistence failures: rethrowing out of the NonCancellable
                    // teardown body would crash the IME process after the cancel succeeded.
                    runCatching {
                        sessionPersistence.persistAudioSource(
                            audioSource = audioSource,
                            rawText = null,
                            processedText = null,
                            errorMessage = TEARDOWN_CANCEL_MESSAGE,
                            reason = InlineCapturePersistReason.USER_CANCELLED,
                        )
                    }.onFailure { Log.e(tag, "Failed to persist teardown-window cancel", it) }
                }
            }
            recordingStateManager.onRecordingCompleted()
            transcription.resetPhase()
            clearPendingStop()
            return
        }

        if (audioSource == null) {
            abandonActiveLiveCapture()
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

        recordingStateManager.transitionToStopping()

        when (
            tryDurableKeyboardHandoff(
                audioSource = audioSource,
                suppressHistory = suppressHistory,
                forceDurable = localRouteRescueMessage != null,
            )
        ) {
            HandoffDisposition.RESOLVED -> return
            HandoffDisposition.INLINE_LOCAL -> {
                if (localRouteRescueMessage != null) {
                    runCatching {
                        sessionPersistence.persistAudioSource(
                            audioSource = audioSource,
                            rawText = null,
                            processedText = null,
                            errorMessage = localRouteRescueMessage,
                            reason = InlineCapturePersistReason.RESCUE,
                        )
                    }.onFailure { Log.e(tag, "Failed to persist finalized keyboard recording", it) }
                    recordingStateManager.onRecordingCompleted()
                    transcription.resetPhase()
                    clearPendingStop()
                    return
                }
            }

            HandoffDisposition.INLINE_FALLBACK -> Unit
        }

        sessionPersistence.prepareAudioSource(audioSource)

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
                                latencyObserver = latencyTrace?.asObserver(),
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

    /**
     * Normal keyboard dictation leaves the IME at this boundary. Incognito keeps the existing
     * local pipeline so a no-learning field never creates durable history.
     *
     * The result tells the caller whether the stop is done, the selected local route still owns
     * the untouched source, or a cloud handoff failed before taking ownership.
     */
    private suspend fun tryDurableKeyboardHandoff(
        audioSource: InlineAudioSource,
        suppressHistory: Boolean,
        forceDurable: Boolean,
    ): HandoffDisposition {
        val capturePlan = activeLiveCapture.getAndSet(null)
        if (suppressHistory || audioSource !is InlineAudioSource.PcmFloatFile) {
            if (capturePlan?.transcriptionEngine == TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3) {
                val released =
                    runCatching { keyboardDictationHandoff.releaseLiveCaptureForInline(capturePlan) }
                        .onFailure { Log.e(tag, "Could not release the cloud capture for local processing", it) }
                        .isSuccess
                if (!released) {
                    runCatching { keyboardDictationHandoff.abandonLiveCapture(capturePlan) }
                        .onFailure { Log.e(tag, "Could not discard the unreleasable cloud capture", it) }
                    recordingStateManager.onRecordingError(HANDOFF_FAILED_MESSAGE)
                    transcription.setError(HANDOFF_FAILED_MESSAGE)
                    clearPendingStop()
                    return HandoffDisposition.RESOLVED
                }
            }
            return HandoffDisposition.INLINE_FALLBACK
        }

        val result =
            try {
                keyboardDictationHandoff.handoff(
                    KeyboardDictationHandoffRequest(
                        audioSource = audioSource,
                        llmEnabled = llmEnabled.value,
                        processingModeId = sessionProcessingMode().id,
                        transcriptionEngine = capturePlan?.transcriptionEngine,
                        forceDurable = forceDurable,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Durable keyboard handoff failed unexpectedly", e)
                KeyboardDictationHandoffResult.Failed(
                    message = HANDOFF_FAILED_MESSAGE,
                    sourceAvailableForInlineFallback =
                        capturePlan?.transcriptionEngine != TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3 &&
                            java.io.File(audioSource.path).isFile,
                )
            }

        return when (result) {
            KeyboardDictationHandoffResult.InlineLocal ->
                if (capturePlan?.transcriptionEngine != TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3) {
                    HandoffDisposition.INLINE_LOCAL
                } else {
                    activeStopToken.set(null)
                    recordingStateManager.onRecordingError(HANDOFF_FAILED_MESSAGE)
                    transcription.setError(HANDOFF_FAILED_MESSAGE)
                    clearPendingStop()
                    HandoffDisposition.RESOLVED
                }

            is KeyboardDictationHandoffResult.Durable -> {
                val cancelled = cancelRequestedDuringTeardown.getAndSet(false)
                if (cancelled) {
                    val discarded =
                        runCatching { keyboardDictationHandoff.discard(result.recordingId) }
                            .onFailure { Log.e(tag, "Failed to discard a durably queued dictation", it) }
                            .getOrDefault(false)
                    if (!discarded) {
                        transcription.setError(HANDOFF_CANCEL_FAILED_MESSAGE)
                    } else {
                        transcription.resetPhase()
                    }
                } else {
                    transcription.resetPhase()
                }
                recordingStateManager.onRecordingCompleted()
                clearPendingStop()
                HandoffDisposition.RESOLVED
            }

            is KeyboardDictationHandoffResult.Failed -> {
                if (result.sourceAvailableForInlineFallback &&
                    capturePlan?.transcriptionEngine != TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3
                ) {
                    HandoffDisposition.INLINE_FALLBACK
                } else {
                    activeStopToken.set(null)
                    recordingStateManager.onRecordingError(result.message)
                    transcription.setError(result.message)
                    clearPendingStop()
                    HandoffDisposition.RESOLVED
                }
            }
        }
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
                        awaitLiveCaptureJournal()
                        capture.cancelCapture()
                        abandonActiveLiveCapture()
                        recordingStateManager.onRecordingCompleted()
                        transcription.resetPhase()
                        clearPendingStop()
                    }
            } else if (userInitiated && teardownJob?.isActive == true) {
                // MIC-017: the cancel landed inside the stop-teardown window — the stop
                // already flipped isRecording and the transcription pipeline does not exist
                // yet, so there is no job to cancel. Record the intent for
                // finishStopAfterTeardown to consume instead of silently dropping the cancel
                // (the dictation would commit against the user's intent). Mark the
                // user-cancel too so the persistence layer respects the save preference.
                // A non-user-initiated cancel (service destruction) deliberately does NOT
                // set the flag: destroy keeps rescuing the capture through the unmarked
                // pipeline-cancellation path exactly as before.
                cancelRequestedDuringTeardown.set(true)
                transcription.markUserCancelled()
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
        stopRollingTranscription()
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
                awaitLiveCaptureJournal()
                capture.cancelCapture()
                abandonActiveLiveCapture()
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
        suppressHistory: Boolean = historyPersistenceSuppressed(),
        onComplete: () -> Unit = {},
    ) {
        if (!isRecording.value) {
            return
        }
        capture.abandonAudioFocus()
        recordingJob?.cancel()
        recordingJob = null
        stopRollingTranscription()
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
                        awaitLiveCaptureJournal()
                        val audioSource = capture.stopAsAudioSource()
                        if (suppressHistory) {
                            // Keep the established no-learning behavior. A focus-close is a
                            // rescue, so its audio still survives even though normal incognito
                            // completions leave no history.
                            runCatching {
                                persistence.persistAudioSource(
                                    audioSource = audioSource,
                                    rawText = null,
                                    processedText = null,
                                    errorMessage = errorMessage,
                                    reason = InlineCapturePersistReason.RESCUE,
                                )
                            }.onFailure { Log.e(tag, "Failed to persist finalized keyboard recording", it) }
                            recordingStateManager.onRecordingCompleted()
                            clearPendingStop()
                        } else {
                            // There is no longer a valid input target, so a rare pre-ownership
                            // handoff failure may use the local pipeline only as a rescue. Its
                            // commit callback always refuses and the transcript lands in history.
                            finishStopAfterTeardown(
                                audioSource = audioSource,
                                commitText = { false },
                                suppressHistory = false,
                                localRouteRescueMessage = errorMessage,
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(tag, "Failed to finalize keyboard recording", e)
                    recordingStateManager.onRecordingError(errorMessage)
                } finally {
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
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val LIVE_TRANSCRIPTION_INITIAL_DELAY_MS = 3_000L
        private const val LIVE_TRANSCRIPTION_INTERVAL_MS = 6_000L
        private const val LIVE_TRANSCRIPTION_MIN_SECONDS = 2
        private const val STREAMING_TRANSCRIPTION_POLL_MS = 320L
        internal const val STOP_TIMEOUT_IN_PROGRESS_MESSAGE =
            "Transcription is taking longer than expected"
        internal const val STOP_TIMEOUT_RESCUE_MESSAGE =
            "Dictation stop timed out; the captured audio was saved to recordings"

        /**
         * MIC-017: persisted with the USER_CANCELLED reason when a cancel landed inside the
         * stop-teardown window; mirrors the pipeline's own user-cancel persist message.
         */
        internal const val TEARDOWN_CANCEL_MESSAGE = "Dictation cancelled"
        internal const val HANDOFF_FAILED_MESSAGE =
            "Could not save the dictation for background transcription"
        internal const val HANDOFF_CANCEL_FAILED_MESSAGE =
            "The dictation was queued before it could be discarded"
        internal const val FIRST_AUDIO_FAILED_MESSAGE =
            "The microphone started, but no audio arrived"
    }
}

private const val LIVE_TRANSCRIPTION_WINDOW_SECONDS = 8
private const val LIVE_TRANSCRIPTION_MAX_OVERLAP_WORDS = 16

internal fun readRollingPcmWindow(
    path: String,
    availableSamples: Int,
    sampleRate: Int,
): FloatArray {
    if (availableSamples <= 0 || sampleRate <= 0) return FloatArray(0)
    return runCatching {
        RandomAccessFile(path, "r").use { input ->
            val completeSamples = minOf(availableSamples.toLong(), input.length() / Float.SIZE_BYTES).toInt()
            val windowSamples = sampleRate * LIVE_TRANSCRIPTION_WINDOW_SECONDS
            val startSample = (completeSamples - windowSamples).coerceAtLeast(0)
            val count = completeSamples - startSample
            input.seek(startSample.toLong() * Float.SIZE_BYTES)
            FloatArray(count) {
                Float.fromBits(Integer.reverseBytes(input.readInt()))
            }
        }
    }.getOrDefault(FloatArray(0))
}

internal fun readIncrementalPcmSamples(
    path: String,
    startSample: Int,
    availableSamples: Int,
): FloatArray {
    if (startSample < 0 || availableSamples <= startSample) return FloatArray(0)
    return runCatching {
        RandomAccessFile(path, "r").use { input ->
            val completeSamples = minOf(availableSamples.toLong(), input.length() / Float.SIZE_BYTES).toInt()
            if (completeSamples <= startSample) return@use FloatArray(0)
            input.seek(startSample.toLong() * Float.SIZE_BYTES)
            FloatArray(completeSamples - startSample) {
                Float.fromBits(Integer.reverseBytes(input.readInt()))
            }
        }
    }.getOrDefault(FloatArray(0))
}

internal fun mergeRollingTranscript(
    previous: String?,
    next: String,
): String? {
    val cleanNext = next.trim()
    if (cleanNext.isEmpty()) return previous
    val cleanPrevious = previous?.trim().orEmpty()
    if (cleanPrevious.isEmpty()) return cleanNext
    val previousWords = cleanPrevious.split(Regex("\\s+"))
    val nextWords = cleanNext.split(Regex("\\s+"))
    val maxOverlap = minOf(LIVE_TRANSCRIPTION_MAX_OVERLAP_WORDS, previousWords.size, nextWords.size)
    val overlap =
        (maxOverlap downTo 1).firstOrNull { size ->
            previousWords.takeLast(size).map(::rollingComparableWord) ==
                nextWords.take(size).map(::rollingComparableWord)
        } ?: 0
    return (previousWords + nextWords.drop(overlap)).joinToString(" ")
}

private fun rollingComparableWord(word: String): String =
    word.lowercase().filter(Char::isLetterOrDigit)

internal class DictationLatencyTrace(
    private val logTag: String,
    private val nowNanos: () -> Long = System::nanoTime,
    private val sink: (String) -> Unit = { message -> Log.i(logTag, message) },
) {
    private val startedAtNanos = nowNanos()
    private var previousAtNanos = startedAtNanos

    @Synchronized
    fun mark(event: String) {
        val now = nowNanos()
        val totalMs = (now - startedAtNanos) / NANOS_PER_MILLISECOND
        val stageMs = (now - previousAtNanos) / NANOS_PER_MILLISECOND
        previousAtNanos = now
        sink("Dictation latency event=$event totalMs=$totalMs stageMs=$stageMs")
    }

    fun asObserver(): InlineDictationLatencyObserver =
        object : InlineDictationLatencyObserver {
            override fun onDecodeStarted() = mark("decode_started")

            override fun onRawTranscriptReady() = mark("raw_transcript_ready")

            override fun onAiStarted() = mark("ai_started")

            override fun onAiCompleted() = mark("ai_completed")

            override fun onCommitCompleted(accepted: Boolean) =
                mark(if (accepted) "commit_completed" else "commit_refused")
        }

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
