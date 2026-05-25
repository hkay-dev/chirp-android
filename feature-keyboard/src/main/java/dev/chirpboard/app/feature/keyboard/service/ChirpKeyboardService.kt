package dev.chirpboard.app.feature.keyboard.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingStartResult
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.core.preferences.KeyboardPreferences
import dev.chirpboard.app.feature.keyboard.haptic.HapticFeedback
import dev.chirpboard.app.core.audio.AudioFocusManager
import dev.chirpboard.app.core.audio.recorder.AudioEncoder
import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
import dev.chirpboard.app.feature.keyboard.state.KeyboardState
import dev.chirpboard.app.feature.keyboard.ui.KeyboardUI
import dev.chirpboard.app.core.recording.RecordingPermissionGuard
import dev.chirpboard.app.feature.llm.TextProcessor
import dev.chirpboard.app.feature.llm.model.ProcessingMode
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Input Method Service for voice-to-text keyboard functionality.
 * Integrates with RecordingStateManager to coordinate with app and widget recording.
 */
@AndroidEntryPoint
class ChirpKeyboardService :
    InputMethodService(),
    LifecycleOwner,
    SavedStateRegistryOwner {
    companion object {
        private const val TAG = "ChirpKeyboard"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "chirp_service"
        private const val SHUTDOWN_PERSISTENCE_TIMEOUT_MS = 5_000L

        // Intent action for opening main activity - will be resolved at runtime
        private const val MAIN_ACTIVITY_CLASS = "dev.chirpboard.app.MainActivity"
    }

    @Inject lateinit var recordingStateManager: RecordingStateManager

    @Inject lateinit var textProcessor: TextProcessor

    @Inject lateinit var keyboardPreferences: KeyboardPreferences

    @Inject lateinit var modeRepository: ProcessingModeRepository

    @Inject lateinit var obsidianManager: dev.chirpboard.app.feature.obsidian.ObsidianManager

    @Inject lateinit var obsidianPreferences: dev.chirpboard.app.feature.obsidian.settings.ObsidianPreferences

    @Inject lateinit var recognizerProvider: TranscriberProvider

    @Inject lateinit var recordingRepository: RecordingRepository

    @Inject lateinit var inputDeviceSelector: AudioInputDeviceSelector

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override fun onEvaluateFullscreenMode(): Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<KeyboardState>(KeyboardState.ModelNotReady)
    private val state = _state.asStateFlow()

    private val recorder by lazy { VoiceRecorder(this, scope, inputDeviceSelector) }
    private val audioEncoder = AudioEncoder()
    private lateinit var audioFocusManager: AudioFocusManager
    private var phoneCallHandler: PhoneCallHandler? = null

    /** Stores samples from last recording for potential persistence */
    private var lastRecordingSamples: FloatArray? = null

    private val _llmEnabled = MutableStateFlow(true)
    private val _currentMode = MutableStateFlow<ProcessingMode>(ProcessingMode.Proofread)
    private val _microphoneGain = MutableStateFlow(1.0f)

    private var recordingJob: Job? = null
    private var finalizationJob: Job? = null
    private var persistenceJob: Job? = null
    private val transcriptionPipeline by lazy {
        KeyboardTranscriptionPipeline(
            tag = TAG,
            recognizerProvider = recognizerProvider,
            textProcessor = textProcessor,
            keyboardPreferences = keyboardPreferences,
            obsidianManager = obsidianManager,
            obsidianPreferences = obsidianPreferences,
            persistenceScope = persistenceScope,
            filesDirProvider = { filesDir },
            audioEncoder = audioEncoder,
            recordingRepository = recordingRepository,
            getBufferedSamples = { lastRecordingSamples },
            setBufferedSamples = { lastRecordingSamples = it },
            getPersistenceJob = { persistenceJob },
            setPersistenceJob = { persistenceJob = it },
        )
    }
    private var composeView: ComposeView? = null

    // Custom recomposer for IME
    private val recomposerScope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main)
    private val recomposer = Recomposer(recomposerScope.coroutineContext)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate - Starting foreground service to keep model in memory")

        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        // Start foreground service to prevent Android from killing us
        KeyboardForegroundNotification.start(
            service = this,
            channelId = CHANNEL_ID,
            notificationId = NOTIFICATION_ID,
            mainActivityClass = MAIN_ACTIVITY_CLASS,
        )

        // Initialize audio focus manager
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioFocusManager = AudioFocusManager(audioManager)
        KeyboardServiceStartup.configureAudioFocusInterrupts(
            tag = TAG,
            audioFocusManager = audioFocusManager,
            currentState = { _state.value },
            onRecordingInterrupted = ::stopAndTranscribe,
        )

        // Initialize phone call handler to stop recording during calls
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        phoneCallHandler =
            KeyboardServiceStartup.registerPhoneCallInterrupts(
                telephonyManager = telephonyManager,
                mainExecutor = ContextCompat.getMainExecutor(this),
                tag = TAG,
                currentState = { _state.value },
                onRecordingInterrupted = ::stopAndTranscribe,
            )

        // Start the recomposer
        KeyboardServiceStartup.startRecomposer(
            recomposerScope = recomposerScope,
            recomposer = recomposer,
        )

        // Observe preferences
        KeyboardServiceStartup.observePreferences(
            scope = scope,
            keyboardPreferences = keyboardPreferences,
            onLlmEnabledChanged = { enabled -> _llmEnabled.value = enabled },
            onMicrophoneGainChanged = { gain -> _microphoneGain.value = gain },
        )

        // Observe processing mode changes
        KeyboardServiceStartup.observeProcessingMode(
            scope = scope,
            modeRepository = modeRepository,
            onModeChanged = { mode -> _currentMode.value = mode },
        )

        // Sync keyboard state from RecordingStateManager for recording-related states.
        // This ensures keyboard state reflects the global recording state (e.g., if another
        // source stops recording, or if an error occurs in RecordingStateManager).
        // Keyboard-specific states (Transcribing, Polishing, ModelNotReady, LlmError, Downloading)
        // are still managed directly by this service.
        KeyboardServiceStartup.observeRecordingState(
            scope = scope,
            recordingStateManager = recordingStateManager,
            currentState = { _state.value },
            onStateChanged = { state -> _state.value = state },
            tag = TAG,
        )

        // Initialize recognizer
        initializeModel()
    }

    private fun toggleLlm() {
        val newValue = !_llmEnabled.value
        scope.launch {
            keyboardPreferences.setLlmEnabled(newValue)
        }
    }

    private fun changeMode(mode: ProcessingMode) {
        scope.launch {
            modeRepository.setMode(mode)
        }
    }

    private fun onBackspace() {
        deletePreviousCharacter(currentInputConnection)
    }

    private fun onSpace() {
        commitSpace(currentInputConnection)
    }

    private fun onMoveCursor(delta: Int) {
        moveCursor(currentInputConnection, delta)
    }

    private fun initializeModel() {
        if (recognizerProvider.isReady() || _state.value is KeyboardState.Downloading) {
            if (recognizerProvider.isReady()) _state.value = KeyboardState.Idle
            return
        }

        scope.launch {
            if (recognizerProvider.isModelDownloaded()) {
                Log.d(TAG, "Model downloaded, initializing recognizer...")
                if (recognizerProvider.initialize()) {
                    Log.d(TAG, "Recognizer ready!")
                    _state.value = KeyboardState.Idle
                } else {
                    Log.e(TAG, "Failed to initialize recognizer")
                    _state.value = KeyboardState.Error("Failed to load model")
                }
            } else {
                Log.d(TAG, "Model not downloaded yet")
                _state.value = KeyboardState.ModelNotReady
            }
        }
    }

    override fun onCreateInputView(): View {
        return composeView ?: ComposeView(this).also { view ->
            view.compositionContext = recomposer
            view.setViewTreeLifecycleOwner(this@ChirpKeyboardService)
            view.setViewTreeSavedStateRegistryOwner(this@ChirpKeyboardService)
            view.setContent {
                val llmEnabled by _llmEnabled.collectAsStateWithLifecycle()
                val currentMode by _currentMode.collectAsStateWithLifecycle()
                val state by state.collectAsStateWithLifecycle()
                KeyboardUI(
                    state = state,
                    waveformBuffer = recorder.waveformBuffer,
                    sampleCountFlow = recorder.sampleCountFlow,
                    llmEnabled = llmEnabled,
                    currentMode = currentMode,
                    onTap = ::onTap,
                    onCancel = ::cancelRecording,
                    onRestart = ::restartRecording,
                    onToggleLlm = ::toggleLlm,
                    onModeChange = ::changeMode,
                    onBackspace = ::onBackspace,
                    onSpace = ::onSpace,
                    onMoveCursor = ::onMoveCursor,
                    onOpenApp = ::openMainActivity,
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
        Log.d(TAG, "onStartInputView, current state: ${_state.value}")

        // Check mic permission
        if (!RecordingPermissionGuard.hasRecordAudioPermission(this)) {
            _state.value = KeyboardState.Error(RecordingPermissionGuard.PERMISSION_DENIED_MESSAGE)
            return
        }

        // Always try to initialize if not ready
        if (!recognizerProvider.isReady()) {
            initializeModel()
        } else if (_state.value is KeyboardState.ModelNotReady) {
            _state.value = KeyboardState.Idle
        }

        // Re-check model state
        if (_state.value is KeyboardState.ModelNotReady) {
            initializeModel()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Log.d(TAG, "onFinishInputView")

        // Stop recording if active
        if (_state.value is KeyboardState.Recording) {
            finalizeActiveRecording(
                errorMessage = "Recording stopped when the keyboard closed before transcription finished",
            )
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy - Stopping foreground service")
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (_state.value is KeyboardState.Recording) {
            finalizeActiveRecording(
                errorMessage = "Recording stopped because the keyboard service was destroyed",
            )
        }
        awaitPendingPersistence()

        // Unregister phone call handler
        phoneCallHandler?.unregister()
        phoneCallHandler = null

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        recomposer.cancel()
        recomposerScope.cancel()
        scope.cancel()
        persistenceScope.cancel()

        composeView?.disposeComposition()
        composeView = null

        recorder.close()
        super.onDestroy()
    }

    private fun openMainActivity() {
        val intent = Intent().setClassName(this, MAIN_ACTIVITY_CLASS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun onTap() {
        val currentState = _state.value
        when (currentState) {
            is KeyboardState.Idle -> {
                startRecording()
            }

            is KeyboardState.Recording -> {
                stopAndTranscribe()
            }

            is KeyboardState.ModelNotReady -> {
                initializeModel()
            }

            is KeyboardState.Error -> {
                initializeModel()
            }

            is KeyboardState.LlmError -> {
                _state.value = KeyboardState.Idle
            }

            else -> {}
        }
    }

    private fun startRecording() {
        Log.d(TAG, "Starting recording")

        // Check mic permission
        if (!RecordingPermissionGuard.hasRecordAudioPermission(this)) {
            _state.value = KeyboardState.Error(RecordingPermissionGuard.PERMISSION_DENIED_MESSAGE)
            return
        }

        // Request audio focus first
        when (audioFocusManager.requestFocus()) {
            is AudioFocusManager.FocusResult.Denied -> {
                Log.e(TAG, "Audio focus denied - another app is using audio")
                _state.value = KeyboardState.Error("Another app is using audio")
                return
            }

            is AudioFocusManager.FocusResult.Granted -> {
                Log.d(TAG, "Audio focus granted, starting recording")
            }

            else -> { /* shouldn't happen */ }
        }

        // Check if another source is recording
        val result = recordingStateManager.tryStartRecording(RecordingOrigin.KEYBOARD)
        when (result) {
            is RecordingStartResult.Success -> {
                // Proceed with recording
            }

            is RecordingStartResult.AlreadyRecording -> {
                val sourceMessage =
                    when (result.currentOrigin) {
                        RecordingOrigin.APP -> "app"
                        RecordingOrigin.WIDGET -> "widget"
                        RecordingOrigin.KEYBOARD -> "keyboard"
                    }
                Toast.makeText(this, "Microphone in use by $sourceMessage", Toast.LENGTH_SHORT).show()
                audioFocusManager.abandonFocus()
                return
            }
        }

        // Set microphone gain before recording
        recorder.gainMultiplier = _microphoneGain.value

        // Set error callback to handle recording errors
        recorder.onRecordingError = { error ->
            Log.e(TAG, "Recording error: ${error.userMessage}")
            audioFocusManager.abandonFocus()
            recordingStateManager.onRecordingError(error.userMessage)
            _state.value = KeyboardState.Error(error.userMessage)
        }

        recorder.onLimitReached = {
            Log.w(TAG, "Recording reached maximum limit, automatically transcribing")
            scope.launch {
                if (_state.value is KeyboardState.Recording) {
                    stopAndTranscribe()
                }
            }
        }

        scope.launch {
            if (!recorder.start()) {
                _state.value = KeyboardState.Error("Failed to start recording")
                recordingStateManager.onRecordingError("Failed to start recording")
                audioFocusManager.abandonFocus()
                return@launch
            }

            HapticFeedback.onRecordStart(this@ChirpKeyboardService)
            _state.value = KeyboardState.Recording
            recordingStateManager.onRecordingStarted("keyboard_temp_recording")

            recordingJob =
                scope.launch {
                    recorder.collectSamples()
                }
        }
    }

    private fun stopAndTranscribe() {
        Log.d(TAG, "Stopping recording and transcribing")

        // Abandon audio focus since we're done recording
        audioFocusManager.abandonFocus()

        HapticFeedback.onRecordStop(this)
        recordingJob?.cancel()
        val samples = recorder.stop()

        // Store samples for potential persistence
        lastRecordingSamples = samples

        if (samples.isEmpty()) {
            lastRecordingSamples = null
            recordingStateManager.onRecordingCompleted()
            _state.value = KeyboardState.Idle
            return
        }

        _state.value = KeyboardState.Transcribing
        recordingStateManager.transitionToStopping()
        recordingStateManager.startStoppingTimeout(fileSizeBytes = 0L)
        scope.launch(Dispatchers.Default) {
            transcriptionPipeline.run(
                samples = samples,
                currentMode = _currentMode.value,
                llmEnabled = _llmEnabled.value,
                commitText = { text -> currentInputConnection?.commitText(text, 1) },
                onStateChanged = { state -> _state.value = state },
                onRecordingCompleted = { recordingStateManager.onRecordingCompleted() },
                onRecordingError = { message -> recordingStateManager.onRecordingError(message) },
            )
        }
    }

    private fun cancelRecording() {
        Log.d(TAG, "Canceling recording")
        audioFocusManager.abandonFocus()
        HapticFeedback.onRecordStop(this)
        recordingJob?.cancel()
        recorder.stop()
        lastRecordingSamples = null
        recordingStateManager.onRecordingCompleted()
        _state.value = KeyboardState.Idle
    }

    private fun restartRecording() {
        cancelRecording()
        startRecording()
    }

    private fun finalizeActiveRecording(errorMessage: String) {
        if (_state.value !is KeyboardState.Recording) {
            return
        }

        Log.w(TAG, "Finalizing active recording: $errorMessage")
        audioFocusManager.abandonFocus()
        recordingJob?.cancel()

        val samples = recorder.stop()
        lastRecordingSamples = samples.takeIf { it.isNotEmpty() }
        _state.value = KeyboardState.Idle

        finalizationJob =
            persistenceScope.launch {
                try {
                    transcriptionPipeline.persistBufferedKeyboardCapture(
                        rawText = null,
                        processedText = null,
                        errorMessage = errorMessage,
                    )
                } finally {
                    recordingStateManager.onRecordingCompleted()
                }
            }
    }

    private fun awaitPendingPersistence() {
        val latch = CountDownLatch(1)
        persistenceScope.launch {
            try {
                awaitPendingPersistenceSuspend()
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(SHUTDOWN_PERSISTENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS) &&
            ((finalizationJob?.isActive == true) || (persistenceJob?.isActive == true))
        ) {
            Log.w(TAG, "Timed out waiting for keyboard recording persistence during shutdown")
        }
    }

    private suspend fun awaitPendingPersistenceSuspend() {
        withTimeoutOrNull(SHUTDOWN_PERSISTENCE_TIMEOUT_MS) {
            finalizationJob?.join()
            persistenceJob?.join()
        }
    }
}
