package dev.chirpboard.app.feature.keyboard.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.PausableMonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.audio.AudioFocusManager
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.AudioInputDeviceSummary
import dev.chirpboard.app.core.audio.AudioSettings
import dev.chirpboard.app.core.audio.AudioSettingsStore
import dev.chirpboard.app.core.llm.ProcessingModePort
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.preferences.KeyboardPreferences
import dev.chirpboard.app.core.recording.KeyboardPendingStopStore
import dev.chirpboard.app.core.recording.KeyboardRecordingStopBridge
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingPermissionGuard
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.transcription.InlineCapturePersistence
import dev.chirpboard.app.core.transcription.InlineTranscriptionPhase
import dev.chirpboard.app.core.transcription.InlineTranscriptionPort
import dev.chirpboard.app.core.transcription.KeyboardDictationHandoff
import dev.chirpboard.app.core.transcription.TranscriptionRoutingStore
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.transcription.StreamingTranscriberProvider
import dev.chirpboard.app.core.ui.components.InputDevicePickerUiState
import dev.chirpboard.app.core.ui.theme.DynamicColorPreference
import dev.chirpboard.app.feature.keyboard.BuildConfig
import dev.chirpboard.app.feature.keyboard.R
import dev.chirpboard.app.feature.keyboard.quickcapture.QuickCaptureSessionImpl
import dev.chirpboard.app.feature.keyboard.session.KeyboardSessionCoordinator
import dev.chirpboard.app.feature.keyboard.session.VoicePanelPhase
import dev.chirpboard.app.feature.keyboard.ui.KeyboardScreen
import javax.inject.Inject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.runtime.Recomposer

@AndroidEntryPoint
class ChirpKeyboardService :
    InputMethodService(),
    LifecycleOwner,
    SavedStateRegistryOwner {
    companion object {
        private const val TAG = "ChirpKeyboard"
        private const val MAIN_ACTIVITY_CLASS = "dev.chirpboard.app.MainActivity"
        private const val CONFIG_CHANGE_GRACE_MS = 2000L
private const val VOICE_SUBTYPE_MODE = "voice"
    }

    @Inject lateinit var recordingStateManager: RecordingStateManager
    @Inject lateinit var keyboardPreferences: KeyboardPreferences
    @Inject lateinit var modePort: ProcessingModePort
    @Inject lateinit var recognizerProvider: TranscriberProvider
    @Inject lateinit var streamingRecognizerProvider: StreamingTranscriberProvider
    @Inject lateinit var modelReadinessGate: SpeechModelReadinessGate
    @Inject lateinit var inputDeviceSelector: AudioInputDeviceSelector
    @Inject lateinit var audioSettingsStore: AudioSettingsStore
    @Inject lateinit var inlineTranscription: InlineTranscriptionPort
    @Inject lateinit var inlineCapturePersistence: InlineCapturePersistence
    @Inject lateinit var keyboardDictationHandoff: KeyboardDictationHandoff
    @Inject lateinit var transcriptionRoutingStore: TranscriptionRoutingStore
    @Inject lateinit var keyboardStopBridge: KeyboardRecordingStopBridge
    @Inject lateinit var pendingStopStore: KeyboardPendingStopStore
    @Inject lateinit var dynamicColorPreference: DynamicColorPreference

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override fun onEvaluateFullscreenMode(): Boolean = false
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    // A SupervisorJob only isolates sibling collectors from each other; an exception escaping one
    // of them still reaches the default handler, which kills the IME process mid-typing. Log and
    // let the surviving collectors carry on instead.
    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Main +
                CoroutineExceptionHandler { _, error ->
                    Log.e(TAG, "Keyboard service coroutine failed", error)
                },
        )
    private val recomposerScope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main)

    // PRF-3: unlike the platform windowRecomposer, a hand-rolled Recomposer has no pausable frame
    // clock, so the idle mic aura's infinite transition would keep posting Choreographer callbacks
    // at vsync rate for as long as the IME service lives — including while the keyboard window is
    // HIDDEN and the user is in other apps. Wrapping the AndroidUiDispatcher clock in a
    // PausableMonotonicFrameClock lets onWindowHidden stop all withFrameNanos work and
    // onWindowShown resume it (recomposition catches up with any state written while paused).
    private val recomposerFrameClock =
        PausableMonotonicFrameClock(
            requireNotNull(recomposerScope.coroutineContext[MonotonicFrameClock]) {
                "AndroidUiDispatcher.Main must provide a MonotonicFrameClock"
            },
        )
    private val recomposer = Recomposer(recomposerScope.coroutineContext + recomposerFrameClock)

    private lateinit var audioFocusManager: AudioFocusManager
    private lateinit var coordinator: KeyboardSessionCoordinator
    private val inputSessionGuard = KeyboardInputSessionGuard()
    private var phoneCallHandler: PhoneCallHandler? = null
    private var stopBridgeRegistration: KeyboardRecordingStopBridge.Registration? = null
    private var composeView: ComposeView? = null
    private var configChangeGraceUntilUptimeMs = 0L
    private var lastKnownConfigSnapshot: KeyboardConfigSnapshot? = null
    private var orphanedRecordingFinalizeJob: Job? = null
    private var pendingImeSwitchCleanup = false

    // RELY-5: true while this bind was entered through the auxiliary voice subtype (another
    // keyboard handed us its mic key); the flag drives the switch back after the dictation ends.
    private var voiceSubtypeSession = false

    // IME-5: the stray-z cleanup may only be considered for the FIRST client bind after service
    // creation (a genuine IME switch recreates this service) and only within a freshness window,
    // so an ordinary app switch can never delete a legitimate trailing z/Z.
    private var straySwitchCleanupArmed = false
    private var serviceCreatedAtUptimeMs = 0L

    /** Current editor's action key (IME-1); read by the composition, written on input start. */
    private val editorImeAction = mutableStateOf(KeyboardImeAction.Enter)

    /** PRF-3 defensive gate: ambient keyboard animations compose only while the window shows. */
    private val windowShownState = mutableStateOf(true)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        lastKnownConfigSnapshot = keyboardConfigSnapshotOf(resources.configuration)
        serviceCreatedAtUptimeMs = SystemClock.uptimeMillis()
        straySwitchCleanupArmed =
            shouldArmStraySwitchCleanup(
                lastProcessExitTimestampMs = latestProcessExitTimestampMs(),
                nowMs = System.currentTimeMillis(),
            )

        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioFocusManager = AudioFocusManager(audioManager)

        val capture =
            QuickCaptureSessionImpl(
                context = this,
                scope = scope,
                inputDeviceSelector = inputDeviceSelector,
                recordingStateManager = recordingStateManager,
                audioFocusManager = audioFocusManager,
            )

        coordinator =
            KeyboardSessionCoordinator(
                tag = TAG,
                context = this,
                scope = scope,
                capture = capture,
                transcription = inlineTranscription,
                persistence = inlineCapturePersistence,
                keyboardDictationHandoff = keyboardDictationHandoff,
                transcriptionRoutingStore = transcriptionRoutingStore,
                transcriberProvider = recognizerProvider,
                recordingStateManager = recordingStateManager,
                keyboardPreferences = keyboardPreferences,
                modePort = modePort,
                pendingStopStore = pendingStopStore,
                modelReadinessGate = modelReadinessGate,
                streamingTranscriberProvider = streamingRecognizerProvider,
            )

        coordinator.commitTextProvider = inputSessionGuard.commitTextProvider(::commitToInputSession)
        // IME-3: incognito (no-personalized-learning) sessions suppress history persistence only;
        // sampled by the coordinator at stop time.
        coordinator.historyPersistenceSuppressed = { inputSessionGuard.isLearningSuppressed }

        audioFocusManager.onFocusLost = { lossKind ->
            // Transient loss or ducking (notification ding, assistant chirp) must not end
            // dictation; only a permanent loss stops and commits the session.
            if (lossKind == AudioFocusManager.FocusLossKind.PERMANENT &&
                coordinator.isRecordingActive()
            ) {
                stopAndTranscribeForCurrentInput()
            }
        }

        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        phoneCallHandler =
            telephonyManager?.let { manager ->
                PhoneCallHandler(manager, ContextCompat.getMainExecutor(this)).apply {
                    onCallStateChanged = { inCall ->
                        if (inCall && coordinator.isRecordingActive()) {
                            stopAndTranscribeForCurrentInput()
                        }
                    }
                    register()
                }
            }

        // PRF-3: the recompose loop awaits frames from the CALLING coroutine's MonotonicFrameClock,
        // so the pausable clock must be in this launch context (the Recomposer constructor context
        // only covers effect coroutines like rememberInfiniteTransition's withFrameNanos).
        recomposerScope.launch(AndroidUiDispatcher.Main + recomposerFrameClock) {
            recomposer.runRecomposeAndApplyChanges()
        }

        scope.launch {
            modelReadinessGate.state
                .map { it is dev.chirpboard.app.core.modelreadiness.ModelReadinessState.Ready }
                .distinctUntilChanged()
                .collect { ready ->
                    if (ready && !recognizerProvider.isReady()) {
                        coordinator.initializeModel()
                    }
                }
        }

        coordinator.refreshModelStatus()

        // KBD-KSO: FLAG_KEEP_SCREEN_ON follows the dictation session, not window visibility.
        // Tying it to visibility kept the display lit for as long as any text field had focus.
        scope.launch {
            recordingStateManager.state
                .map(::keyboardKeepsScreenAwake)
                .distinctUntilChanged()
                .collect { keepAwake -> updateImeKeepScreenOn(window?.window, keepAwake) }
        }

        // RELY-4: while an LLM-off dictation records, stream the live partial transcript into
        // the editor as composing text — a session that dies mid-flight loses only the tail,
        // because the editor keeps the composed prefix. LLM-on sessions keep the panel-only
        // preview (the polished history entry may differ from the raw partial). Deliberately
        // NOT cleared when recording ends: the final commit replaces the composed region, and
        // if the pipeline dies first the surviving preview IS the crash protection.
        scope.launch {
            coordinator.uiState.collect { state ->
                val previewText =
                    state.partialTranscript?.takeIf {
                        state.voicePanel == VoicePanelPhase.Recording &&
                            !state.llmEnabled &&
                            // A cancel can race a queued Recording emission; the live check keeps
                            // the stale partial from re-composing into the editor after the user
                            // already dismissed the session.
                            coordinator.isRecordingActive()
                    }
                if (previewText != null) {
                    inputSessionGuard.updateComposingPreview(currentInputConnection, previewText)
                } else if (state.voicePanel == VoicePanelPhase.Recording) {
                    // LLM toggled on mid-recording (or the partial vanished): drop the preview
                    // so stale composing text never underlies the eventual commit.
                    inputSessionGuard.clearComposingPreview(currentInputConnection)
                }
            }
        }

        stopBridgeRegistration =
            keyboardStopBridge.registerStopHandler {
                stopAndTranscribeForCurrentInput()
            }
        drainPendingKeyboardStopIfNeeded()

    }

    private fun drainPendingKeyboardStopIfNeeded() {
        scope.launch {
            val state = recordingStateManager.state.value
            pendingStopStore.reconcileStale(state)
            val shouldDrainPendingStop =
                pendingStopStore.peek() != null &&
                    (
                        coordinator.isRecordingActive() ||
                            (state is RecordingState.Stopping && state.origin == RecordingOrigin.KEYBOARD)
                    )
            if (shouldDrainPendingStop && stopAndTranscribeForCurrentInput()) {
                pendingStopStore.clear()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        configChangeGraceUntilUptimeMs = SystemClock.uptimeMillis() + CONFIG_CHANGE_GRACE_MS
        super.onConfigurationChanged(newConfig)
        // LIF-08: the cached ComposeView can be detached from its dead parent exactly when the
        // window's configuration dispatch lands, leaving LocalConfiguration (and with it
        // isSystemInDarkTheme/density) stale until the IME process restarts. Forwarding the new
        // configuration ourselves makes the update deterministic; safe on detached views.
        composeView?.dispatchConfigurationChanged(newConfig)
        // LIF-09: adopt the new configuration once its restart burst is over. Without this a
        // config flip while the keyboard is hidden (rotation, dark mode, fold) leaves the
        // snapshot stale until the next onStartInputView, so isConfigChangeInFlight stays true
        // for every later dismissal and routes them through the delayed orphaned-finalize path.
        // Restarts arriving after this line are still covered by the grace window; restarts
        // arriving before it saw the old snapshot and matched the diff.
        lastKnownConfigSnapshot = keyboardConfigSnapshotOf(newConfig)
    }

    // The view-restart messages from a config change can arrive before or after our own
    // onConfigurationChanged, so check both the grace window and a live snapshot diff. The
    // snapshot covers orientation AND uiMode/fontScale/density/screen size (LIF-07): a dark-mode
    // flip or split-screen resize mid-dictation must preserve the session exactly like rotation.
    private fun isConfigChangeInFlight(): Boolean =
        SystemClock.uptimeMillis() < configChangeGraceUntilUptimeMs ||
            keyboardConfigSnapshotOf(resources.configuration) != lastKnownConfigSnapshot

    /**
     * Timestamp of this package's most recent process death, or null when none is recorded.
     * Used to keep the stray-z cleanup disarmed when a service create is really the system
     * restarting the IME after a kill (which then binds like a fresh IME switch).
     */
    private fun latestProcessExitTimestampMs(): Long? =
        runCatching {
            (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .getHistoricalProcessExitReasons(packageName, 0, 1)
                .firstOrNull()
                ?.timestamp
        }.getOrNull()

    override fun onBindInput() {
        super.onBindInput()
        // IME-5: onBindInput fires for EVERY freshly bound client app, not only after an IME
        // switch. Arm the cleanup once per service lifetime, and only within moments of
        // onCreate — the only window in which a SwiftKey-mic stray letter can exist.
        if (straySwitchCleanupArmed) {
            straySwitchCleanupArmed = false
            pendingImeSwitchCleanup =
                shouldAttemptStraySwitchCleanup(SystemClock.uptimeMillis() - serviceCreatedAtUptimeMs)
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // The window instance can change across show cycles; re-apply the dictation-scoped flag.
        updateImeKeepScreenOn(window?.window, enabled = coordinator.isRecordingActive())
        windowShownState.value = true
        if (recomposerFrameClock.isPaused) {
            recomposerFrameClock.resume()
        }
        // PRF-3: restore RESUMED so collectAsStateWithLifecycle collectors pick the state back up.
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        updateImeKeepScreenOn(window?.window, enabled = false)
        windowShownState.value = false
        // PRF-3: with the keyboard window hidden there is nothing to draw — drop the lifecycle to
        // CREATED so lifecycle-aware flow collection suspends, and pause the frame clock so the
        // idle aura's infinite transition stops burning a CPU wakeup per vsync all day. Dictation
        // cannot be active here (onFinishInputView finalizes it), and in-flight transcription,
        // commits and rescue persistence run on ordinary dispatchers, untouched by the clock.
        moveLifecycleDownToCreated()
        if (!recomposerFrameClock.isPaused) {
            recomposerFrameClock.pause()
        }
    }

    private fun moveLifecycleDownToCreated() {
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
    }

    override fun onCreateInputView(): View {
        composeView?.let { existing ->
            // The framework discards its old view hierarchy on configuration changes but
            // leaves the cached view attached to the dead parent; detach it or addView throws.
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        return ComposeView(this).also { view ->
            view.compositionContext = recomposer
            view.setViewTreeLifecycleOwner(this@ChirpKeyboardService)
            view.setViewTreeSavedStateRegistryOwner(this@ChirpKeyboardService)
            // INS-1: insets are not auto-dispatched to a ComposeView inside an IME window, so
            // WindowInsets.navigationBars/systemGestures would read 0 in Compose and the keyboard
            // root would draw flush to the bottom edge — letting Samsung's IME-switcher + collapse
            // buttons overlap backspace/Space. Opt out of decor-fitting and re-request the apply so
            // the system bottom inset (when present) reaches Compose. The KeyboardScreen still
            // floors the bottom pad with a minimum IME-nav strip, which is the load-bearing clear
            // because this device (Good Lock hiding the gesture hint) reports a zero gesture inset.
            window?.window?.let { imeWindow ->
                WindowCompat.setDecorFitsSystemWindows(imeWindow, false)
            }
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
                ViewCompat.onApplyWindowInsets(v, insets)
            }
            view.setContent {
                val uiState by coordinator.uiState.collectAsStateWithLifecycle()
                // DECISIONS (Color/brand): collect the shared "Use system colors (Material You)"
                // preference so the keyboard matches whatever palette the app is showing. Brand
                // lavender remains the default until the user opts in.
                val useDynamicColor by dynamicColorPreference.useDynamicColor
                    .collectAsStateWithLifecycle(
                        initialValue = DynamicColorPreference.DEFAULT_USE_DYNAMIC_COLOR,
                    )
                // AUDIODEV: live device list + persisted preference + per-session active
                // device, feeding the compact picker chip beside the AI toggle.
                val inputDevices by inputDeviceSelector.availableDevices.collectAsStateWithLifecycle()
                val audioSettings by audioSettingsStore.settings
                    .collectAsStateWithLifecycle(initialValue = AudioSettings())
                val activeInputDevice by inputDeviceSelector.activeDevice.collectAsStateWithLifecycle()
                // MIC-004: the picker is "session live" only while the KEYBOARD origin owns
                // the recording, so the chip pins the live session's actual device and the
                // sheet shows the applies-next-session note — and an app/widget/recognition
                // capture can never mark this surface live.
                val recordingState by recordingStateManager.state.collectAsStateWithLifecycle()
                KeyboardScreen(
                    uiState = uiState,
                    waveformBuffer = coordinator.capture.waveformBuffer,
                    sampleCountFlow = coordinator.capture.sampleCountFlow,
                    onMicTap = ::onMicTapForCurrentInput,
                    onCancel = {
                        // RELY-4: a user cancel is the one end that must NOT leave the streamed
                        // preview behind — the user just said "discard this dictation".
                        inputSessionGuard.clearComposingPreview(currentInputConnection)
                        coordinator.cancelRecording()
                        // RELY-5: a cancelled hand-off dictation still returns to the keyboard
                        // that sent us here — leaving Chirp up would strand the user.
                        returnFromVoiceSubtypeIfNeeded()
                    },
                    onRestart = {
                        // A restart discards the current dictation like a cancel does; the
                        // streamed preview must go with it, or a failed re-start strands the
                        // old partial in the editor with nothing left to replace it.
                        inputSessionGuard.clearComposingPreview(currentInputConnection)
                        coordinator.restartRecording()
                    },
                    onToggleLlm = coordinator::toggleLlm,
                    onModeChange = coordinator::changeMode,
                    // Every typing key runs a raw InputConnection action that finishes the
                    // composing region; the guard must drop its streamed preview first or the
                    // final commit lands a second copy of the dictation after the finalized one.
                    onBackspace = { runEditorKeyAction { deletePreviousCharacter(currentInputConnection) } },
                    onBackspaceWord = { runEditorKeyAction { deletePreviousWord(currentInputConnection) } },
                    onSpace = { runEditorKeyAction { commitSpace(currentInputConnection) } },
                    onMoveCursor = { delta -> runEditorKeyAction { moveCursor(currentInputConnection, delta) } },
                    imeAction = editorImeAction.value,
                    onImeAction = {
                        runEditorKeyAction { performImeAction(currentInputConnection, editorImeAction.value) }
                    },
                    onOpenApp = ::openMainActivity,
                    onDismissError = {
                        coordinator.clearErrorOverlay()
                        inlineTranscription.resetPhase()
                    },
                    dynamicColor = useDynamicColor,
                    windowShown = windowShownState.value,
                    inputDevicePicker =
                        InputDevicePickerUiState(
                            devices = inputDevices,
                            policy = audioSettings.inputDevicePolicy,
                            manualKey = audioSettings.manualDeviceAddress,
                            manualName = audioSettings.manualDeviceName,
                            activeDevice = activeInputDevice,
                            sessionLive = keyboardPickerSessionLive(recordingState),
                        ),
                    onSelectInputDeviceAutomatic = ::selectInputDeviceAutomatic,
                    onSelectInputDevice = ::selectInputDevice,
                    // The IME window cannot host a runtime-permission prompt; route to the
                    // app (ERR-8 pattern) where the settings picker offers the grant.
                    onRequestBluetoothNames = ::openMainActivity,
                )
            }
            composeView = view
        }
    }

    override fun onStartInputView(
        info: EditorInfo?,
        restarting: Boolean,
    ) {
        super.onStartInputView(info, restarting)
        orphanedRecordingFinalizeJob?.cancel()
        orphanedRecordingFinalizeJob = null
        val preserveSession = restarting && isConfigChangeInFlight()
        val previousHistorySuppressed = inputSessionGuard.isLearningSuppressed
        lastKnownConfigSnapshot = keyboardConfigSnapshotOf(resources.configuration)
        editorImeAction.value = resolveImeAction(info)
        val cleanupStraySwitchCharacter = pendingImeSwitchCleanup && !restarting && !preserveSession
        pendingImeSwitchCleanup = false
        inputSessionGuard.startInput(info, preserveSession = preserveSession, restarting = restarting)
        if (inputSessionGuard.isSensitiveInput) {
            if (coordinator.isRecordingActive()) {
                coordinator.finalizeActiveRecording(
                    errorMessage = getString(R.string.keyboard_sensitive_input_disabled),
                    suppressHistory = previousHistorySuppressed,
                )
            }
            // IME-4: a password field is not an error — typing aids stay usable; only the voice
            // panel swaps to a neutral "dictation off" notice (no Retry). Reset the transcription
            // phase too: a sticky Error from a previous field would otherwise outrank the notice
            // with a Retry that is a guaranteed no-op here (no capture-commit session).
            coordinator.clearErrorOverlay()
            inlineTranscription.resetPhase()
            coordinator.setSensitiveInput(true)
            return
        }
        coordinator.setSensitiveInput(false)
        // IME-16: the "didn't catch that" hint is scoped to the dictation that produced it.
        // Re-engaging an editor (field change or restart) clears it; a config-change restart
        // preserves the whole session, hint included. Sticky Errors keep their stronger
        // lifecycle (dismissed explicitly or by the sensitive-field reset above).
        if (!preserveSession && inlineTranscription.phase.value is InlineTranscriptionPhase.NoSpeech) {
            inlineTranscription.resetPhase()
        }
        if (cleanupStraySwitchCharacter) {
            removeStraySwitchCharacter(currentInputConnection)
        }
        commitDeferredDictationIfMatching()
        if (!RecordingPermissionGuard.hasRecordAudioPermission(this)) {
            coordinator.setMicPermissionError(RecordingPermissionGuard.PERMISSION_DENIED_MESSAGE)
            return
        }
        coordinator.clearErrorOverlay()
        coordinator.refreshModelStatus()
        // LOAD-1/LOAD-2: warm the shared recognizer into RAM the instant the keyboard appears
        // (idempotent — initializeModel() no-ops when already ready or a load is in flight), so a
        // post-process-death bind reloads it under the masked "warming" mic instead of stalling
        // the user's first dictation on the in-memory load after they have already spoken.
        coordinator.initializeModel()
        coordinator.prepareStreamingPreview()
        drainPendingKeyboardStopIfNeeded()
        maybeAutoStartVoiceSubtypeDictation()
    }

    /**
     * RELY-5: the auxiliary "voice" subtype exists so compliant third-party keyboards can hand
     * their mic key to Chirp through the platform's IME-switching APIs. Arriving under it means
     * the user asked to dictate right now — start immediately instead of showing an idle panel —
     * and [returnFromVoiceSubtypeIfNeeded] hands control back once the dictation commits.
     */
    private fun maybeAutoStartVoiceSubtypeDictation() {
        val inputMethodManager = getSystemService(InputMethodManager::class.java)
        val subtypeMode = inputMethodManager?.currentInputMethodSubtype?.mode
        if (subtypeMode != VOICE_SUBTYPE_MODE) {
            voiceSubtypeSession = false
            return
        }
        if (voiceSubtypeSession || coordinator.isRecordingActive()) return
        voiceSubtypeSession = true
        onMicTapForCurrentInput()
    }

    private fun returnFromVoiceSubtypeIfNeeded() {
        if (!voiceSubtypeSession) return
        voiceSubtypeSession = false
        if (!switchToPreviousInputMethod()) {
            Log.w(TAG, "Could not return to the previous keyboard after voice input")
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (!finishingInput && isConfigChangeInFlight()) {
            // Rotation tears the input view down and restarts it moments later; keep the
            // dictation session alive so in-progress recording and pending commits survive.
            scheduleOrphanedRecordingFinalize()
            return
        }
        val suppressHistory = inputSessionGuard.isLearningSuppressed
        inputSessionGuard.finishInput()
        // voiceSubtypeSession deliberately survives here: the view routinely finishes between
        // arriving under the voice subtype and the commit, and dropping the latch would strand
        // the user on Chirp instead of returning to their keyboard. It clears only when the
        // switch-back runs or the next bind arrives under a non-voice subtype.
        finalizeRecordingForClosedKeyboard(suppressHistory)
    }

    private fun scheduleOrphanedRecordingFinalize() {
        orphanedRecordingFinalizeJob?.cancel()
        orphanedRecordingFinalizeJob =
            scope.launch {
                delay(CONFIG_CHANGE_GRACE_MS)
                val suppressHistory = inputSessionGuard.isLearningSuppressed
                inputSessionGuard.finishInput()
                finalizeRecordingForClosedKeyboard(suppressHistory)
            }
    }

    private fun finalizeRecordingForClosedKeyboard(suppressHistory: Boolean) {
        if (coordinator.isRecordingActive()) {
            coordinator.finalizeActiveRecording(
                errorMessage = getString(R.string.keyboard_closed_during_dictation),
                suppressHistory = suppressHistory,
            )
        }
    }

    override fun onDestroy() {
        updateImeKeepScreenOn(window?.window, enabled = false)
        stopBridgeRegistration?.let(keyboardStopBridge::clearStopHandler)
        stopBridgeRegistration = null
        // Service destruction is not a user cancel. A live recorder must be stopped into its
        // durable handoff path; cancelRecording would delete the only capture. Once recorder
        // teardown has already started, the non-user cancel still rescues the in-flight source.
        if (coordinator.isRecordingActive()) {
            coordinator.finalizeActiveRecording(
                errorMessage = getString(R.string.keyboard_closed_during_dictation),
                suppressHistory = inputSessionGuard.isLearningSuppressed,
            )
        } else {
            coordinator.cancelRecording(userInitiated = false)
        }
        coordinator.destroy()
        phoneCallHandler?.unregister()
        phoneCallHandler = null
        // Wait out any off-main stop/finalize/cancel RECORDER teardown before closing the
        // recorder. Both run stopToFileBacked()/cancelCapture() on IO under the recorder's
        // sampleLock; without this join capture.close() (on main) could win the lock and delete
        // the just-captured dictation temp PCM. The wait is bounded by the 5-50ms recorder
        // teardown only: the durable-handoff tail (file move + Room insert + WorkManager
        // enqueue) is NonCancellable, survives the scope.cancel() below, and rescues the staged
        // capture itself if the pipeline can no longer launch, so blocking main on it here
        // would just risk an ANR for nothing.
        coordinator.awaitInFlightTeardown()
        coordinator.capture.close()
        moveLifecycleDownToCreated()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        // Un-pause the frame clock (if the window was hidden) so recomposer cancellation and
        // composition disposal can quiesce without waiting on a frame that will never come.
        if (recomposerFrameClock.isPaused) {
            recomposerFrameClock.resume()
        }
        recomposer.cancel()
        recomposerScope.cancel()
        scope.cancel()
        composeView?.disposeComposition()
        composeView = null
        super.onDestroy()
    }

    /**
     * Runs a typing-key action against the live editor with the dictation preview taken out of
     * the composing region first. The raw actions all call `finishComposingText()` themselves,
     * which would otherwise finalize the preview without the guard ever learning about it.
     */
    private fun runEditorKeyAction(action: () -> Unit) {
        inputSessionGuard.onExternalComposingFinish(currentInputConnection)
        action()
    }

    private fun openMainActivity() {
        startActivity(
            Intent().setClassName(this, MAIN_ACTIVITY_CLASS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    /**
     * AUDIODEV picker actions. Persisted via the shared [AudioSettingsStore], so the
     * choice applies to the NEXT capture start on EVERY surface (keyboard, recorder,
     * recognition) — never a mid-session swap. Each selection is a single atomic
     * DataStore edit (MIC-005): a capture starting concurrently can never read the new
     * manual key under the old policy.
     */
    private fun selectInputDeviceAutomatic() {
        scope.launch {
            audioSettingsStore.selectAutomatic()
        }
    }

    private fun selectInputDevice(device: AudioInputDeviceSummary) {
        scope.launch {
            audioSettingsStore.selectManualDevice(device.selectionKey, device.productName)
        }
    }

    private fun onMicTapForCurrentInput() {
        val session = inputSessionGuard.captureCommitSession()
        if (session == null) {
            // Sensitive fields already show the dictation-off notice; for any other dead session
            // (no active input) explain the real condition — there is no field to type into.
            if (!inputSessionGuard.isSensitiveInput) {
                coordinator.setSessionError(getString(R.string.keyboard_no_active_field))
            }
            return
        }
        // A finalized preview remembered from an earlier session must never dedup against this
        // new dictation's commit — from here on, whatever is in the editor stays.
        inputSessionGuard.onDictationStarting()
        // KBD-3: a tap while the model is still warming must drive the load forward rather than
        // dead-end. initializeModel() is idempotent (guards on isReady()/in-flight job), so this
        // promotes the user's intent into a warm even if the IME-bind warm has not landed yet.
        coordinator.initializeModel()
        coordinator.onMicTap { text -> commitToInputSession(session, text) }
    }

    private fun stopAndTranscribeForCurrentInput(): Boolean {
        if (!coordinator.isRecordingActive()) {
            return false
        }
        val session = inputSessionGuard.captureCommitSession()
        if (session == null) {
            // No live editor to commit into, but the microphone is open. This is the stop path
            // for a phone call, permanent audio-focus loss, and the widget's stop command;
            // refusing here left a hot mic with no visible UI. Stop into the durable rescue
            // path instead — the transcript lands in history.
            coordinator.finalizeActiveRecording(
                errorMessage = getString(R.string.keyboard_stopped_without_field),
                suppressHistory = inputSessionGuard.isLearningSuppressed,
            )
            return true
        }
        return coordinator.stopAndTranscribe { text -> commitToInputSession(session, text) }
    }

    private fun commitToInputSession(
        session: KeyboardInputCommitSession,
        text: String,
    ): Boolean {
        // Debug-only: the soak harness arms a synthetic refusal to exercise the recovery paths.
        // A release build must never be able to reject a real dictation commit for a test.
        if (BuildConfig.DEBUG &&
            dev.chirpboard.app.core.reliability.DictationReliabilityMetrics.consumeCommitRefusal()
        ) {
            Log.w(TAG, "Reliability soak injected a commit refusal")
            coordinator.setSessionError(getString(R.string.keyboard_input_changed))
            return false
        }
        val result = inputSessionGuard.commitIfCurrent(session, currentInputConnection, text)
        when (result) {
            KeyboardDictationCommitResult.COMMITTED -> Unit
            KeyboardDictationCommitResult.COMMITTED_AFTER_RETRY -> {
                Log.w(TAG, "Dictation commit landed only after a verification retry")
                dev.chirpboard.app.core.reliability.DictationReliabilityMetrics.countEvent(
                    dev.chirpboard.app.core.reliability.DictationReliabilityMetric.COMMIT_VERIFY_MISMATCHES,
                )
            }
            KeyboardDictationCommitResult.REFUSED -> {
                Log.w(TAG, "Skipped dictation commit because the input session changed")
                dev.chirpboard.app.core.reliability.DictationReliabilityMetrics.countEvent(
                    dev.chirpboard.app.core.reliability.DictationReliabilityMetric.COMMIT_REFUSALS,
                )
                // A refusal can still land through the deferred retry path; when it does, the
                // voice-subtype return below must fire too, so fall through with its outcome.
                val landed = handleRefusedCommit(session, text)
                if (landed) {
                    returnFromVoiceSubtypeIfNeeded()
                }
                return landed
            }
            KeyboardDictationCommitResult.VERIFICATION_FAILED -> {
                Log.w(TAG, "Editor accepted the dictation commit but the text never appeared")
                dev.chirpboard.app.core.reliability.DictationReliabilityMetrics.countEvent(
                    dev.chirpboard.app.core.reliability.DictationReliabilityMetric.COMMIT_VERIFY_MISMATCHES,
                )
                coordinator.setSessionError(commitFailureMessage(session, text))
            }
        }
        if (result.committed) {
            returnFromVoiceSubtypeIfNeeded()
        }
        return result.committed
    }

    /**
     * RELY-3: a refused commit is not always lost — apps restart input around IME transitions,
     * and the transcript often finishes inside that gap. Hold the text against the editor it was
     * captured for, retry immediately (the same editor may already be rebound under a new
     * session), and otherwise leave it for [commitDeferredDictationIfMatching] plus a clipboard
     * copy as the manual fallback.
     */
    private fun handleRefusedCommit(
        session: KeyboardInputCommitSession,
        text: String,
    ): Boolean {
        if (inputSessionGuard.deferCommit(session, text, SystemClock.elapsedRealtime())) {
            if (commitDeferredDictationIfMatching()) {
                return true
            }
            val message =
                if (!inputSessionGuard.hasDeferredCommit) {
                    // The immediate retry consumed the deferral (verification failure); nothing
                    // is pending anymore, so report a plain failure instead of a deferral.
                    commitFailureMessage(session, text)
                } else if (copyFailedCommitToClipboard(text, session.learningSuppressed)) {
                    getString(R.string.keyboard_commit_deferred)
                } else {
                    getString(R.string.keyboard_input_changed)
                }
            coordinator.setSessionError(message)
        } else {
            coordinator.setSessionError(commitFailureMessage(session, text))
        }
        return false
    }

    /**
     * Lands a deferred dictation commit into the editor that just (re)bound, when it matches the
     * one the transcript was captured against and the deferral window is still open. The deferral
     * is cleared only after the commit lands, so a failed attempt can retry on the next rebind.
     */
    private fun commitDeferredDictationIfMatching(): Boolean {
        val pending =
            inputSessionGuard.deferredCommitTextForCurrentEditor(SystemClock.elapsedRealtime())
                ?: return false
        val session = inputSessionGuard.captureCommitSession() ?: return false
        val result = inputSessionGuard.commitIfCurrent(session, currentInputConnection, pending)
        if (!result.committed) {
            if (result == KeyboardDictationCommitResult.VERIFICATION_FAILED) {
                // The editor swallowed the batch without surfacing the text. Retrying the same
                // deferral on the next rebind risks committing twice into an editor that did
                // take it silently; drop it and let the clipboard fallback carry the recovery.
                inputSessionGuard.clearDeferredCommit()
            }
            return false
        }
        inputSessionGuard.clearDeferredCommit()
        coordinator.clearErrorOverlay()
        returnFromVoiceSubtypeIfNeeded()
        Log.i(TAG, "Deferred dictation commit landed after the editor rebound")
        return true
    }

    /**
     * RELY-2: when the editor cannot take the text, put it on the clipboard so one long-press
     * still recovers the dictation. The active IME is one of the two callers Android guarantees
     * clipboard writes for, so this cannot be silently dropped the way a background app's write
     * can. Skipped for incognito sessions — their text must not outlive the session — and never
     * reached for sensitive fields, which cannot capture a commit session at all.
     */
    private fun commitFailureMessage(
        session: KeyboardInputCommitSession,
        text: String,
    ): String =
        if (copyFailedCommitToClipboard(text, session.learningSuppressed)) {
            getString(R.string.keyboard_commit_failed_copied)
        } else {
            getString(R.string.keyboard_input_changed)
        }

    private fun copyFailedCommitToClipboard(
        text: String,
        learningSuppressed: Boolean,
    ): Boolean {
        // The suppression flag travels with the captured session: the guard's live flag resets
        // when the editor unbinds, which is exactly when this fallback runs for incognito text.
        if (text.isBlank() || learningSuppressed) return false
        return runCatching {
            val clipboard =
                getSystemService(android.content.ClipboardManager::class.java) ?: return false
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText(getString(R.string.keyboard_clip_label), text),
            )
            true
        }.getOrDefault(false)
    }
}

/** Applies or clears FLAG_KEEP_SCREEN_ON on the IME window. */
internal fun updateImeKeepScreenOn(
    window: Window?,
    enabled: Boolean,
) {
    if (enabled) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

/**
 * MIC-004 (keyboard half): the IME picker counts as "session live" only while the KEYBOARD
 * origin owns the active recording. Origin-scoped so an app/widget/recognition capture never
 * pins the keyboard's chip to a device its own next session will not use.
 */
internal fun keyboardPickerSessionLive(state: RecordingState): Boolean =
    state.isActive && state.activeOrigin == RecordingOrigin.KEYBOARD

/**
 * KBD-KSO: the display stays awake only while the KEYBOARD origin owns an active dictation
 * (through Stopping, so the screen cannot sleep while the transcript is still landing). A merely
 * visible keyboard must not hold the flag, or the screen never times out on any focused field.
 */
internal fun keyboardKeepsScreenAwake(state: RecordingState): Boolean =
    state.isActive && state.activeOrigin == RecordingOrigin.KEYBOARD
