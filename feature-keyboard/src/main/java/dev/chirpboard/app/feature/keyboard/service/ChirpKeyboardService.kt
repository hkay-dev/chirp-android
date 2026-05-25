package dev.chirpboard.app.feature.keyboard.service

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.core.content.ContextCompat
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
import dev.chirpboard.app.core.audio.recorder.AudioEncoder
import dev.chirpboard.app.core.modelreadiness.SpeechModelReadinessGate
import dev.chirpboard.app.core.preferences.KeyboardPreferences
import dev.chirpboard.app.core.recording.KeyboardPendingStopStore
import dev.chirpboard.app.core.recording.KeyboardRecordingStopBridge
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingPermissionGuard
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.transcription.InlineTranscriptionCoordinator
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.keyboard.quickcapture.QuickCaptureSessionImpl
import dev.chirpboard.app.feature.keyboard.session.KeyboardInlineCapturePersistence
import dev.chirpboard.app.feature.keyboard.session.KeyboardSessionCoordinator
import dev.chirpboard.app.feature.keyboard.ui.KeyboardScreen
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
import dev.chirpboard.app.feature.obsidian.ObsidianManager
import dev.chirpboard.app.feature.obsidian.settings.ObsidianPreferences
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    }

    @Inject lateinit var recordingStateManager: RecordingStateManager
    @Inject lateinit var keyboardPreferences: KeyboardPreferences
    @Inject lateinit var modeRepository: ProcessingModeRepository
    @Inject lateinit var obsidianManager: ObsidianManager
    @Inject lateinit var obsidianPreferences: ObsidianPreferences
    @Inject lateinit var recognizerProvider: TranscriberProvider
    @Inject lateinit var modelReadinessGate: SpeechModelReadinessGate
    @Inject lateinit var recordingRepository: RecordingRepository
    @Inject lateinit var inputDeviceSelector: AudioInputDeviceSelector
    @Inject lateinit var inlineTranscription: InlineTranscriptionCoordinator
    @Inject lateinit var keyboardStopBridge: KeyboardRecordingStopBridge
    @Inject lateinit var pendingStopStore: KeyboardPendingStopStore

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override fun onEvaluateFullscreenMode(): Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recomposerScope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main)
    private val recomposer = Recomposer(recomposerScope.coroutineContext)

    private lateinit var audioFocusManager: AudioFocusManager
    private lateinit var coordinator: KeyboardSessionCoordinator
    private var phoneCallHandler: PhoneCallHandler? = null
    private var composeView: ComposeView? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

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

        val persistence =
            KeyboardInlineCapturePersistence(
                persistenceScope = persistenceScope,
                filesDirProvider = { filesDir },
                audioEncoder = AudioEncoder(),
                recordingRepository = recordingRepository,
                keyboardPreferences = keyboardPreferences,
                obsidianManager = obsidianManager,
                obsidianPreferences = obsidianPreferences,
            )

        coordinator =
            KeyboardSessionCoordinator(
                tag = TAG,
                context = this,
                scope = scope,
                capture = capture,
                transcription = inlineTranscription,
                persistence = persistence,
                transcriberProvider = recognizerProvider,
                recordingStateManager = recordingStateManager,
                keyboardPreferences = keyboardPreferences,
                modeRepository = modeRepository,
            )

        audioFocusManager.onFocusLost = {
            if (coordinator.isRecordingActive()) {
                coordinator.stopAndTranscribe { text -> currentInputConnection?.commitText(text, 1) }
            }
        }

        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        phoneCallHandler =
            telephonyManager?.let { manager ->
                PhoneCallHandler(manager, ContextCompat.getMainExecutor(this)).apply {
                    onCallStateChanged = { inCall ->
                        if (inCall && coordinator.isRecordingActive()) {
                            coordinator.stopAndTranscribe { text -> currentInputConnection?.commitText(text, 1) }
                        }
                    }
                    register()
                }
            }

        recomposerScope.launch(AndroidUiDispatcher.Main) {
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
        coordinator.initializeModel()

        keyboardStopBridge.registerStopHandler {
            coordinator.stopAndTranscribe { text -> currentInputConnection?.commitText(text, 1) }
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
            if (shouldDrainPendingStop) {
                try {
                    coordinator.stopAndTranscribe { text -> currentInputConnection?.commitText(text, 1) }
                } finally {
                    pendingStopStore.clear()
                }
            }
        }
    }

    override fun onCreateInputView(): View {
        return composeView ?: ComposeView(this).also { view ->
            view.compositionContext = recomposer
            view.setViewTreeLifecycleOwner(this@ChirpKeyboardService)
            view.setViewTreeSavedStateRegistryOwner(this@ChirpKeyboardService)
            view.setContent {
                val uiState by coordinator.uiState.collectAsStateWithLifecycle()
                KeyboardScreen(
                    uiState = uiState,
                    waveformBuffer = coordinator.capture.waveformBuffer,
                    sampleCountFlow = coordinator.capture.sampleCountFlow,
                    onMicTap = { coordinator.onMicTap { text -> currentInputConnection?.commitText(text, 1) } },
                    onCancel = coordinator::cancelRecording,
                    onRestart = coordinator::restartRecording,
                    onToggleLlm = coordinator::toggleLlm,
                    onModeChange = coordinator::changeMode,
                    onBackspace = { deletePreviousCharacter(currentInputConnection) },
                    onBackspaceWord = { deletePreviousWord(currentInputConnection) },
                    onSpace = { commitSpace(currentInputConnection) },
                    onMoveCursor = { delta -> moveCursor(currentInputConnection, delta) },
                    onOpenApp = ::openMainActivity,
                    onDismissError = {
                        coordinator.setPermissionError(null)
                        inlineTranscription.resetPhase()
                    },
                )
            }
            composeView = view
        }
    }

    override fun onStartInputView(
        info: android.view.inputmethod.EditorInfo?,
        restarting: Boolean,
    ) {
        super.onStartInputView(info, restarting)
        if (!RecordingPermissionGuard.hasRecordAudioPermission(this)) {
            coordinator.setPermissionError(RecordingPermissionGuard.PERMISSION_DENIED_MESSAGE)
            return
        }
        if (!recognizerProvider.isReady()) {
            coordinator.initializeModel()
        } else {
            coordinator.refreshModelStatus()
        }
        drainPendingKeyboardStopIfNeeded()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (coordinator.isRecordingActive()) {
            coordinator.finalizeActiveRecording(
                errorMessage = "Recording stopped when the keyboard closed before transcription finished",
            )
        }
    }

    override fun onDestroy() {
        keyboardStopBridge.clearStopHandler()
        phoneCallHandler?.unregister()
        phoneCallHandler = null
        coordinator.capture.close()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        recomposer.cancel()
        recomposerScope.cancel()
        scope.cancel()
        persistenceScope.cancel()
        composeView?.disposeComposition()
        composeView = null
        super.onDestroy()
    }

    private fun openMainActivity() {
        startActivity(
            Intent().setClassName(this, MAIN_ACTIVITY_CLASS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
