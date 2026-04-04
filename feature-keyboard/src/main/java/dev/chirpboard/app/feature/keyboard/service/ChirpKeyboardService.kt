package dev.chirpboard.app.feature.keyboard.service

import android.content.Context
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingStartResult
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.keyboard.KeyboardPreferences
import dev.chirpboard.app.feature.keyboard.haptic.HapticFeedback
import dev.chirpboard.app.feature.keyboard.recorder.AudioEncoder
import dev.chirpboard.app.feature.keyboard.recorder.AudioFocusManager
import dev.chirpboard.app.feature.keyboard.recorder.VoiceRecorder
import dev.chirpboard.app.feature.keyboard.state.KeyboardState
import dev.chirpboard.app.feature.keyboard.state.toKeyboardState
import dev.chirpboard.app.feature.keyboard.ui.KeyboardUI
import dev.chirpboard.app.feature.llm.TextProcessor
import dev.chirpboard.app.feature.llm.model.ProcessingMode
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Input Method Service for voice-to-text keyboard functionality.
 * Integrates with RecordingStateManager to coordinate with app and widget recording.
 */
@AndroidEntryPoint
class ChirpKeyboardService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {
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
    @Inject lateinit var recognizerProvider: RecognizerProvider
    @Inject lateinit var recordingRepository: RecordingRepository

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<KeyboardState>(KeyboardState.ModelNotReady)
    private val state = _state.asStateFlow()

    private val recorder = VoiceRecorder()
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
            mainActivityClass = MAIN_ACTIVITY_CLASS
        )

        // Initialize audio focus manager
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioFocusManager = AudioFocusManager(audioManager)
        audioFocusManager.onFocusLost = {
            Log.w(TAG, "Audio focus lost during recording")
            if (_state.value is KeyboardState.Recording) {
                // Stop recording gracefully
                stopAndTranscribe()
            }
        }

        // Initialize phone call handler to stop recording during calls
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        telephonyManager?.let { tm ->
            phoneCallHandler = PhoneCallHandler(tm, mainExecutor)
            phoneCallHandler?.onCallStateChanged = { isInCall ->
                if (isInCall && _state.value is KeyboardState.Recording) {
                    Log.w(TAG, "Phone call detected, stopping recording")
                    stopAndTranscribe()
                }
            }
            phoneCallHandler?.register()
        }

        // Start the recomposer
        recomposerScope.launch {
            recomposer.runRecomposeAndApplyChanges()
        }

        // Observe preferences
        scope.launch {
            keyboardPreferences.llmEnabled.collect { enabled ->
                _llmEnabled.value = enabled
            }
        }
        
        scope.launch {
            keyboardPreferences.microphoneGain.collect { gain ->
                _microphoneGain.value = gain
            }
        }

        // Observe processing mode changes
        scope.launch {
            modeRepository.currentMode.collect { mode ->
                _currentMode.value = mode
            }
        }

        // Sync keyboard state from RecordingStateManager for recording-related states.
        // This ensures keyboard state reflects the global recording state (e.g., if another
        // source stops recording, or if an error occurs in RecordingStateManager).
        // Keyboard-specific states (Transcribing, Polishing, ModelNotReady, LlmError, Downloading)
        // are still managed directly by this service.
        scope.launch {
            recordingStateManager.state.collect { recordingState ->
                val currentKeyboardState = _state.value
                
                // Only sync if we're in a recording-related state or the recording state changed
                // Don't override keyboard-specific states like Transcribing, Polishing, etc.
                val isKeyboardSpecificState = currentKeyboardState is KeyboardState.Transcribing ||
                    currentKeyboardState is KeyboardState.Polishing ||
                    currentKeyboardState is KeyboardState.Downloading ||
                    currentKeyboardState is KeyboardState.ModelNotReady ||
                    currentKeyboardState is KeyboardState.LlmError
                
                if (!isKeyboardSpecificState) {
                    val derivedState = recordingState.toKeyboardState()
                    // Only update if different to avoid unnecessary recompositions
                    if (derivedState != currentKeyboardState) {
                        Log.d(TAG, "Syncing state from RecordingStateManager: $recordingState -> $derivedState")
                        _state.value = derivedState
                    }
                }
            }
        }

        // Initialize recognizer
        initializeModel()
    }
    
    private fun toggleLlm() {
        val newValue = !_llmEnabled.value
        _llmEnabled.value = newValue
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
        return ComposeView(this).apply {
            // Set our custom recomposer so Compose doesn't look for ViewTreeLifecycleOwner
            compositionContext = recomposer
            setViewTreeLifecycleOwner(this@ChirpKeyboardService)
            setViewTreeSavedStateRegistryOwner(this@ChirpKeyboardService)
            setContent {
                val llmEnabled = _llmEnabled.collectAsState()
                val currentMode by _currentMode.collectAsState(initial = ProcessingMode.Proofread)
                KeyboardUI(
                    stateFlow = state,
                    amplitudesFlow = recorder.amplitudes,
                    llmEnabled = llmEnabled.value,
                    currentMode = currentMode,
                    onTap = ::onTap,
                    onToggleLlm = ::toggleLlm,
                    onModeChange = ::changeMode,
                    onBackspace = ::onBackspace,
                    onSpace = ::onSpace,
                    onMoveCursor = ::onMoveCursor
                )
            }
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView, current state: ${_state.value}")

        // Check mic permission
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = KeyboardState.Error("Microphone permission required")
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
                errorMessage = "Recording stopped when the keyboard closed before transcription finished"
            )
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy - Stopping foreground service")
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (_state.value is KeyboardState.Recording) {
            finalizeActiveRecording(
                errorMessage = "Recording stopped because the keyboard service was destroyed"
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
        
        super.onDestroy()
    }

    private fun onTap() {
        val currentState = _state.value
        when (currentState) {
            is KeyboardState.Idle -> startRecording()
            is KeyboardState.Recording -> stopAndTranscribe()
            is KeyboardState.ModelNotReady -> initializeModel()
            is KeyboardState.Error -> initializeModel()
            is KeyboardState.LlmError -> _state.value = KeyboardState.Idle
            else -> {}
        }
    }

    private fun startRecording() {
        Log.d(TAG, "Starting recording")

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
                val sourceMessage = when (result.currentOrigin) {
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

        if (!recorder.start()) {
            _state.value = KeyboardState.Error("Failed to start recording")
            recordingStateManager.onRecordingError("Failed to start recording")
            audioFocusManager.abandonFocus()
            return
        }

        HapticFeedback.onRecordStart(this)
        _state.value = KeyboardState.Recording
        recordingStateManager.onRecordingStarted("keyboard_temp_recording")

        recordingJob = scope.launch {
            recorder.collectSamples()
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
        recordingStateManager.beginStopRecording()

        // Launch on Default dispatcher for CPU-intensive work (transcription, LLM)
        // This prevents UI freezing on 120Hz displays where each frame is 8.3ms
        scope.launch(Dispatchers.Default) {
            var rawTextForPersistence: String? = null
            try {
                val correlationId = ReliabilityEventLogger.newCorrelationId("keyboard")
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.TRANSCRIPTION,
                    outcome = ReliabilityOutcome.STARTED,
                    correlationId = correlationId,
                    reasonCode = "keyboard_transcription_started"
                )

                if (!recognizerProvider.isReady()) {
                    Log.e(TAG, "Recognizer not ready for transcription")
                    ReliabilityEventLogger.log(
                        stage = ReliabilityStage.TRANSCRIPTION,
                        outcome = ReliabilityOutcome.FAILURE,
                        correlationId = correlationId,
                        reasonCode = "keyboard_recognizer_not_ready"
                    )
                    withContext(Dispatchers.Main) {
                        persistBufferedKeyboardCapture(
                            rawText = null,
                            processedText = null,
                            errorMessage = "Recognizer not ready"
                        )
                        _state.value = KeyboardState.Error("Recognizer not ready")
                        recordingStateManager.onRecordingError("Recognizer not ready")
                    }
                    return@launch
                }
                
                // CPU-intensive transcription runs on Default thread pool
                val transcriptionOutcome = recognizerProvider.transcribe(samples)
                val mappedOutcome = mapKeyboardTranscriptionOutcome(transcriptionOutcome)
                val rawText = when (mappedOutcome) {
                    is KeyboardTranscriptionResolution.Success -> mappedOutcome.text
                    KeyboardTranscriptionResolution.NoSpeech -> {
                        ReliabilityEventLogger.log(
                            stage = ReliabilityStage.TRANSCRIPTION,
                            outcome = ReliabilityOutcome.SKIPPED,
                            correlationId = correlationId,
                            reasonCode = "keyboard_no_speech"
                        )
                        withContext(Dispatchers.Main) {
                            discardBufferedKeyboardCapture()
                            recordingStateManager.onRecordingCompleted()
                            _state.value = KeyboardState.Idle
                        }
                        return@launch
                    }
                    is KeyboardTranscriptionResolution.Failure -> {
                        ReliabilityEventLogger.log(
                            stage = ReliabilityStage.TRANSCRIPTION,
                            outcome = ReliabilityOutcome.FAILURE,
                            correlationId = correlationId,
                            reasonCode = "keyboard_transcription_failed",
                            message = mappedOutcome.message
                        )
                        withContext(Dispatchers.Main) {
                            persistBufferedKeyboardCapture(
                                rawText = rawTextForPersistence,
                                processedText = null,
                                errorMessage = mappedOutcome.message
                            )
                            _state.value = KeyboardState.Error(mappedOutcome.message)
                            recordingStateManager.onRecordingError(mappedOutcome.message)
                        }
                        return@launch
                    }
                }

                Log.d(TAG, "Transcribed: $rawText")
                rawTextForPersistence = rawText
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.TRANSCRIPTION,
                    outcome = ReliabilityOutcome.SUCCESS,
                    correlationId = correlationId,
                    reasonCode = "keyboard_transcription_completed"
                )

                // State updates on Main thread
                withContext(Dispatchers.Main) {
                    recordingStateManager.onRecordingCompleted()
                }

                // LLM post-processing if enabled
                val mode = _currentMode.value
                if (_llmEnabled.value) {
                    ReliabilityEventLogger.log(
                        stage = ReliabilityStage.ENHANCEMENT,
                        outcome = ReliabilityOutcome.STARTED,
                        correlationId = correlationId,
                        reasonCode = "keyboard_enhancement_started"
                    )
                    withContext(Dispatchers.Main) {
                        _state.value = KeyboardState.Polishing
                    }
                    
                    // LLM processing with 10-second timeout fallback
                    val result = withTimeoutOrNull(10_000L) {
                        textProcessor.process(rawText, mode)
                    }
                    
                    withContext(Dispatchers.Main) {
                        if (result != null) {
                            result.fold(
                                onSuccess = { polishedText ->
                                    Log.d(TAG, "Polished: $polishedText")
                                    ReliabilityEventLogger.log(
                                        stage = ReliabilityStage.ENHANCEMENT,
                                        outcome = ReliabilityOutcome.SUCCESS,
                                        correlationId = correlationId,
                                        reasonCode = "keyboard_enhancement_completed"
                                    )
                                    currentInputConnection?.commitText("$polishedText ", 1)
                                    handleTranscriptionComplete(rawText, polishedText)
                                    _state.value = KeyboardState.Idle
                                },
                                onFailure = { error ->
                                    Log.e(TAG, "LLM failed, using raw text", error)
                                    ReliabilityEventLogger.log(
                                        stage = ReliabilityStage.ENHANCEMENT,
                                        outcome = ReliabilityOutcome.FAILURE,
                                        correlationId = correlationId,
                                        reasonCode = "keyboard_enhancement_failed",
                                        message = error.message
                                    )
                                    currentInputConnection?.commitText("$rawText ", 1)
                                    handleTranscriptionComplete(rawText, null)
                                    _state.value = KeyboardState.LlmError("LLM failed: ${error.message}")
                                }
                            )
                        } else {
                            // Timeout - use raw text as fallback
                            Log.w(TAG, "LLM timed out after 10s, using raw text")
                            ReliabilityEventLogger.log(
                                stage = ReliabilityStage.ENHANCEMENT,
                                outcome = ReliabilityOutcome.FAILURE,
                                correlationId = correlationId,
                                reasonCode = "keyboard_enhancement_timeout"
                            )
                            currentInputConnection?.commitText("$rawText ", 1)
                            handleTranscriptionComplete(rawText, null)
                            _state.value = KeyboardState.Idle
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        currentInputConnection?.commitText("$rawText ", 1)
                        handleTranscriptionComplete(rawText, null)
                        _state.value = KeyboardState.Idle
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                val errorMessage = "Transcription failed: ${e.message}"
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.TRANSCRIPTION,
                    outcome = ReliabilityOutcome.FAILURE,
                    correlationId = ReliabilityEventLogger.newCorrelationId("keyboard"),
                    reasonCode = "keyboard_exception",
                    message = e.message
                )
                withContext(Dispatchers.Main) {
                    persistBufferedKeyboardCapture(
                        rawText = rawTextForPersistence,
                        processedText = null,
                        errorMessage = errorMessage
                    )
                    _state.value = KeyboardState.Error(errorMessage)
                    recordingStateManager.onRecordingError(errorMessage)
                }
            }
        }
    }
    
    /**
     * Handle transcription completion.
     * When saveKeyboardRecordings is ON, saves the recording to database.
     */
    private suspend fun handleTranscriptionComplete(rawText: String, processedText: String?) {
        persistBufferedKeyboardCapture(
            rawText = rawText,
            processedText = processedText
        )
    }
    
    /**
     * Saves a keyboard recording to persistent storage.
     * Encodes audio to M4A and creates Recording + Transcript entities.
     */
    private suspend fun persistBufferedKeyboardCapture(
        rawText: String?,
        processedText: String?,
        errorMessage: String? = null
    ) {
        val shouldSave = keyboardPreferences.saveKeyboardRecordings.first()
        val samples = lastRecordingSamples

        if (!shouldSave || samples == null || samples.isEmpty()) {
            lastRecordingSamples = null
            return
        }

        val sampleSnapshot = samples.copyOf()
        lastRecordingSamples = null
        val persistencePlan = buildKeyboardPersistencePlan(
            rawText = rawText,
            processedText = processedText,
            errorMessage = errorMessage
        )

        val job = persistenceScope.launch {
            saveKeyboardRecording(
                filesDir = filesDir,
                audioEncoder = audioEncoder,
                recordingRepository = recordingRepository,
                persistencePlan = persistencePlan,
                samples = sampleSnapshot
            )
        }
        persistenceJob = job

        try {
            job.join()
        } finally {
            if (persistenceJob === job) {
                persistenceJob = null
            }
        }
    }

    private fun discardBufferedKeyboardCapture() {
        lastRecordingSamples = null
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

        finalizationJob = persistenceScope.launch {
            try {
                persistBufferedKeyboardCapture(
                    rawText = null,
                    processedText = null,
                    errorMessage = errorMessage
                )
            } finally {
                recordingStateManager.onRecordingCompleted()
            }
        }
    }

    private fun awaitPendingPersistence() {
        runBlocking {
            val completed = withTimeoutOrNull(SHUTDOWN_PERSISTENCE_TIMEOUT_MS) {
                finalizationJob?.join()
                persistenceJob?.join()
            }

            if (completed == null &&
                ((finalizationJob?.isActive == true) || (persistenceJob?.isActive == true))
            ) {
                Log.w(TAG, "Timed out waiting for keyboard recording persistence during shutdown")
            }
        }
    }

}
