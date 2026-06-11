package dev.chirpboard.app.feature.keyboard.session

import android.content.Context
import android.util.Log
import dev.chirpboard.app.core.llm.ProcessingMode
import dev.chirpboard.app.core.llm.ProcessingModeListItem
import dev.chirpboard.app.core.llm.ProcessingModePort
import dev.chirpboard.app.core.preferences.KeyboardPreferences
import dev.chirpboard.app.core.quickcapture.QuickCaptureStartResult
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.transcription.InlineCapturePersistence
import dev.chirpboard.app.core.transcription.InlineTranscriptionPhase
import dev.chirpboard.app.core.transcription.InlineTranscriptionPort
import dev.chirpboard.app.core.transcription.InlineTranscriptionRequest
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.feature.keyboard.haptic.HapticFeedback
import dev.chirpboard.app.feature.keyboard.quickcapture.QuickCaptureSessionImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
) {
    private val isRecording = MutableStateFlow(false)
    private val permissionError = MutableStateFlow<String?>(null)
    private val modelBanner = MutableStateFlow(ModelBannerState.Initializing)
    private val modelInitFailedMessage = MutableStateFlow<String?>(null)
    private val llmEnabled = MutableStateFlow(true)
    private val currentMode = MutableStateFlow<ProcessingMode>(ProcessingMode.Proofread)
    private val availableModes = MutableStateFlow<List<ProcessingModeListItem>>(emptyList())

    private var recordingJob: Job? = null
    private var startJob: Job? = null
    private var transcriptionJob: Job? = null
    private var modelInitJob: Job? = null

    val uiState: StateFlow<KeyboardUiState> =
        combine(
            combine(isRecording, transcription.phase, modelBanner) { recording, phase, banner ->
                Triple(recording, phase, banner)
            },
            combine(modelInitFailedMessage, llmEnabled, currentMode, permissionError) { initFailed, llm, mode, permError ->
                listOf(initFailed, llm, mode, permError)
            },
            availableModes,
        ) { captureState, prefsState, modes ->
            val (recording, phase, banner) = captureState
            @Suppress("UNCHECKED_CAST")
            val initFailed = prefsState[0] as String?
            @Suppress("UNCHECKED_CAST")
            val llm = prefsState[1] as Boolean
            @Suppress("UNCHECKED_CAST")
            val mode = prefsState[2] as ProcessingMode
            @Suppress("UNCHECKED_CAST")
            val permError = prefsState[3] as String?
            mapKeyboardUiState(
                isRecording = recording,
                transcriptionPhase = phase,
                modelBanner = banner,
                modelInitFailedMessage = initFailed,
                llmEnabled = llm,
                processingMode = mode,
                availableModes = modes,
                permissionError = permError,
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

    init {
        recordingStateManager.setStoppingTimeoutHandler(RecordingOrigin.KEYBOARD) {
            cancelRecording()
            recordingStateManager.onRecordingError("Failed to stop dictation")
        }

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

        capture.onRecordingError = { error ->
            scope.launch {
                capture.abandonAudioFocus()
                recordingStateManager.onRecordingError(error.userMessage)
                isRecording.value = false
                transcription.setError(error.userMessage)
            }
        }

        capture.onLimitReached = {
            scope.launch {
                if (isRecording.value) {
                    stopAndTranscribe(commitText = {})
                }
            }
        }
    }

    fun refreshModelStatus() {
        modelBanner.value =
            when {
                transcriberProvider.isReady() -> ModelBannerState.None
                modelInitJob?.isActive == true -> ModelBannerState.Initializing
                !transcriberProvider.isModelDownloaded() -> ModelBannerState.NotDownloaded
                else -> ModelBannerState.Initializing
            }
    }

    fun initializeModel() {
        if (transcriberProvider.isReady()) {
            refreshModelStatus()
            return
        }
        if (modelInitJob?.isActive == true) {
            return
        }

        modelInitJob =
            scope.launch {
                refreshModelStatus()
                if (!transcriberProvider.isModelDownloaded()) {
                    refreshModelStatus()
                    return@launch
                }
                val initialized =
                    withContext(Dispatchers.Default) {
                        transcriberProvider.initialize()
                    }
                if (initialized) {
                    Log.d(tag, "Recognizer ready")
                    modelInitFailedMessage.value = null
                    refreshModelStatus()
                } else {
                    Log.e(tag, "Failed to initialize recognizer")
                    modelInitFailedMessage.value = "Failed to load model"
                    modelBanner.value = ModelBannerState.InitFailed
                }
            }
    }

    fun onMicTap(commitText: (String) -> Unit) {
        val panel = uiState.value.voicePanel
        when {
            isRecording.value -> stopAndTranscribe(commitText)
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
        startJob =
            scope.launch {
                try {
                    when (val result = capture.start()) {
                        is QuickCaptureStartResult.Success -> {
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
                    startJob = null
                }
            }
    }

    fun stopAndTranscribe(commitText: (String) -> Unit): Boolean {
        if (!isRecording.value) {
            return false
        }
        isRecording.value = false

        capture.abandonAudioFocus()
        HapticFeedback.onRecordStop(context)
        recordingJob?.cancel()
        recordingJob = null

        val audioSource = capture.stopAsAudioSource()
        if (audioSource == null) {
            persistence.discardSamples()
            recordingStateManager.onRecordingCompleted()
            transcription.resetPhase()
            return true
        }

        persistence.prepareAudioSource(audioSource)

        recordingStateManager.transitionToStopping()
        recordingStateManager.startStoppingTimeout(fileSizeBytes = 0L)

        transcriptionJob?.cancel()
        transcriptionJob =
            scope.launch(Dispatchers.Default) {
                try {
                    transcription.transcribe(
                        request =
                            InlineTranscriptionRequest(
                                samples = FloatArray(0),
                                llmEnabled = llmEnabled.value,
                                processingModeId = currentMode.value.id,
                                audioSource = audioSource,
                            ),
                        persistence = persistence,
                        commitText = { text -> scope.launch(Dispatchers.Main) { commitText(text) } },
                        onRecordingCompleted = { recordingStateManager.onRecordingCompleted() },
                        onRecordingError = { message -> recordingStateManager.onRecordingError(message) },
                    )
                } finally {
                    transcriptionJob = null
                }
            }
        return true
    }

    fun cancelRecording() {
        val wasRecording = isRecording.value
        if (!wasRecording && transcriptionJob?.isActive != true) {
            return
        }
        transcriptionJob?.cancel()
        transcriptionJob = null
        if (!wasRecording) {
            recordingStateManager.onRecordingCompleted()
            transcription.resetPhase()
            return
        }
        capture.abandonAudioFocus()
        HapticFeedback.onRecordStop(context)
        recordingJob?.cancel()
        recordingJob = null
        capture.cancelCapture()
        persistence.discardSamples()
        recordingStateManager.onRecordingCompleted()
        transcription.resetPhase()
    }

    fun restartRecording() {
        cancelRecording()
        startRecording()
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
        val audioSource = capture.stopAsAudioSource()
        isRecording.value = false
        transcription.resetPhase()
        scope.launch {
            persistence.persistAudioSource(
                audioSource = audioSource,
                rawText = null,
                processedText = null,
                errorMessage = errorMessage,
            )
            recordingStateManager.onRecordingCompleted()
            onComplete()
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
}
