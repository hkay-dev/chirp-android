package dev.chirpboard.app

import android.app.Activity
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.audio.AudioFocusManager
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.AudioSettingsStore
import dev.chirpboard.app.core.audio.recorder.RecordingError
import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
import dev.chirpboard.app.core.llm.ProcessingMode
import dev.chirpboard.app.core.llm.ProcessingModePort
import dev.chirpboard.app.core.preferences.KeyboardPreferences
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingPermissionGuard
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.transcription.InlineAudioSource
import dev.chirpboard.app.core.transcription.InlineCapturePersistReason
import dev.chirpboard.app.core.transcription.InlineCapturePersistence
import dev.chirpboard.app.core.transcription.InlineTranscriptionPhase
import dev.chirpboard.app.core.transcription.InlineTranscriptionPort
import dev.chirpboard.app.core.transcription.InlineTranscriptionRequest
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.ui.theme.ChirpTheme
import dev.chirpboard.app.core.ui.theme.DynamicColorPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Readiness of the on-device transcription model for the voice recognition dialog.
 */
internal enum class VoiceRecognitionModelState {
    Initializing,
    Ready,
    Unavailable,
}

/**
 * Terminal error surfaced inside the recognition dialog before the result is returned, so
 * failures are explained instead of the sheet silently vanishing (ERR-9/ERR-23/ERR-27).
 * [speechErrorCode] is the SpeechRecognizer.ERROR_* the caller ultimately receives
 * (mapped to a RecognizerIntent result code by [recognizerIntentResultCodeFor]).
 */
internal sealed interface VoiceRecognitionUiError {
    val speechErrorCode: Int

    /** RECORD_AUDIO is not granted; persistent until the user acts or dismisses (ERR-9). */
    data object PermissionMissing : VoiceRecognitionUiError {
        override val speechErrorCode: Int = SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
    }

    /** Another surface holds the microphone (ERR-23). */
    data class MicBusy(
        val sourceLabel: String,
    ) : VoiceRecognitionUiError {
        override val speechErrorCode: Int = SpeechRecognizer.ERROR_RECOGNIZER_BUSY
    }

    /** The recorder failed to start or stopped unexpectedly mid-capture (ERR-23/IME-2). */
    data object CaptureFailed : VoiceRecognitionUiError {
        override val speechErrorCode: Int = SpeechRecognizer.ERROR_AUDIO
    }

    /** Transcription failed after the user spoke (ERR-27). */
    data class TranscriptionFailed(
        override val speechErrorCode: Int,
        /** True when the inline pipeline's rescue persisted the captured audio. */
        val audioRescued: Boolean,
    ) : VoiceRecognitionUiError

    /** The capture contained no recognizable speech. */
    data object NoSpeech : VoiceRecognitionUiError {
        override val speechErrorCode: Int = SpeechRecognizer.ERROR_NO_MATCH
    }

    /**
     * No speech was detected within the initial-silence budget (the SpeechRecognizer
     * ERROR_SPEECH_TIMEOUT convention). Persistent rather than auto-returning: the
     * dialog shows a gentle "didn't catch anything" state with a retry affordance, and
     * the error code is returned only when the user dismisses without retrying.
     */
    data object NoSpeechTimeout : VoiceRecognitionUiError {
        override val speechErrorCode: Int = SpeechRecognizer.ERROR_SPEECH_TIMEOUT
    }
}

/**
 * Minimal dialog-style activity for voice recognition (like Google's).
 * Handles android.speech.action.RECOGNIZE_SPEECH intents from other apps.
 */
@AndroidEntryPoint
class VoiceRecognitionActivity : ComponentActivity() {
    private val recorder by lazy { VoiceRecorder(this, lifecycleScope, inputDeviceSelector) }
    private val captureGate by lazy { VoiceRecognitionCaptureGate(recordingStateManager) }

    /**
     * The same hardened start/stop/cancel coordinator the [ChirpRecognitionService] uses, so
     * both system-recognition surfaces share one serialized lifecycle and one capture-gate
     * contract (SLOP-18). The activity keeps only its UI, model-readiness gating, and the
     * inline-transcription/result-intent plumbing on top of this.
     */
    private val sessionCoordinator by lazy {
        VoiceRecognitionSessionCoordinator(
            scope = lifecycleScope,
            captureGate = captureGate,
            recorder = recorderControl,
            audioPathLabel = ACTIVITY_AUDIO_PATH_LABEL,
        )
    }

    /**
     * Transient-exclusive audio focus for the capture session (AUD-14): pauses other
     * playback so music is not blasted into the microphone, matching the keyboard and
     * RecordingService capture surfaces. A denied request degrades gracefully.
     */
    private val audioFocus by lazy { AudioFocusManager(getSystemService(AudioManager::class.java)) }

    private val recorderControl =
        object : VoiceRecognitionSessionCoordinator.RecorderControl {
            override suspend fun prepare() {
                recorder.gainMultiplier = audioSettingsStore.currentMicrophoneGain()
                if (audioFocus.requestFocus() == AudioFocusManager.FocusResult.Denied) {
                    Log.w(TAG, "Audio focus denied; capturing without focus")
                }
            }

            override suspend fun start(): Boolean = recorder.start()

            override fun stop(): FloatArray {
                val samples = recorder.stop()
                audioFocus.abandonFocus()
                return samples
            }

            override fun cancel() {
                recorder.cancelCapture()
                audioFocus.abandonFocus()
            }

            override suspend fun collectSamples() {
                try {
                    recorder.collectSamples()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error collecting samples", e)
                }
            }

            // The dialog renders the waveform directly from recorder.sampleCountFlow /
            // waveformBuffer; this stream's only consumer here is the per-session
            // endpointer that terminates an all-silence capture (mirrors the service).
            override suspend fun streamRms(onRms: (Float) -> Unit) {
                recorder.sampleCountFlow.collect { count ->
                    if (count > 0L) {
                        onRms(recorder.waveformBuffer.lastOrNull() ?: 0f)
                    }
                }
            }
        }

    /**
     * Survives activity teardown so audio rescued in [onDestroy] is persisted even while
     * [lifecycleScope] is being cancelled. Persistence work is short and non-cancellable.
     */
    private val rescueScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Inject lateinit var transcriberProvider: TranscriberProvider

    @Inject lateinit var inlineTranscription: InlineTranscriptionPort

    @Inject lateinit var capturePersistence: InlineCapturePersistence

    @Inject lateinit var audioSettingsStore: AudioSettingsStore

    @Inject lateinit var inputDeviceSelector: AudioInputDeviceSelector

    @Inject lateinit var recordingStateManager: RecordingStateManager

    @Inject lateinit var modePort: ProcessingModePort

    @Inject lateinit var keyboardPreferences: KeyboardPreferences

    @Inject lateinit var dynamicColorPreference: DynamicColorPreference
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    private val _shouldDismiss = MutableStateFlow(false)
    private val _partialTranscript = MutableStateFlow("")
    private val _modelState = MutableStateFlow(VoiceRecognitionModelState.Initializing)
    private val _uiError = MutableStateFlow<VoiceRecognitionUiError?>(null)

    /**
     * True when the live capture's teardown was already classified as a discard (user
     * cancel, or the no-speech timeout's pure-silence cancel). The coordinator cancel
     * hops to the IO dispatcher, so the capture gate can still be held when [onDestroy]
     * runs ~250ms later — without this mark that race misfiled a deliberate discard as a
     * system interruption and persisted a "Voice recognition interrupted" rescue entry.
     * Main-thread only; reset by [startRecording] for each new session.
     */
    private var captureTeardownDiscardsAudio = false

    /**
     * IME-6: a caller that sets [RecognizerIntent.EXTRA_SECURE] (keyguard/secure contexts)
     * signals the session must not be stored or sent to cloud post-processing; the whole
     * session runs without persistence and without the LLM path.
     */
    private val secureSession: Boolean
        get() = intent?.getBooleanExtra(RecognizerIntent.EXTRA_SECURE, false) == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "VoiceRecognitionActivity created")

        androidx.core.view.WindowCompat
            .setDecorFitsSystemWindows(window, false)
        val params = window.attributes
        params.gravity = android.view.Gravity.BOTTOM
        params.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
        // MATCH_PARENT (not WRAP_CONTENT) so the window covers the whole screen and FLAG_DIM_BEHIND
        // can scrim the host app behind the sheet (DLG-5/INS-2/LOAD-7). The Compose root still pins
        // the sheet to the bottom via Alignment.BottomCenter, so the visible sheet keeps its height.
        params.height = android.view.WindowManager.LayoutParams.MATCH_PARENT
        // Dim the host app behind the sheet with the Material modal scrim (~32%) so the dialog reads
        // as a focused modal rather than a leaky overlay. Outside taps are handled by the Compose
        // scrim layer's tap-to-cancel; the old FLAG_WATCH_OUTSIDE_TOUCH path never fires with a
        // full-screen window and was removed (IME-24).
        params.flags = params.flags or
            android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND
        params.dimAmount = DIALOG_DIM_AMOUNT
        window.attributes = params

        // IME-2/AUD-17: the recorder's 10-minute cap and mid-capture failures must end the
        // session like a user action instead of leaving a frozen "listening" dialog.
        recorder.onLimitReached = {
            Log.w(TAG, "Recording limit reached; stopping recognition capture")
            stopFromSystemInterrupt()
        }
        recorder.onRecordingError = { error -> onCaptureError(error) }
        audioFocus.onFocusLost = { kind ->
            if (kind == AudioFocusManager.FocusLossKind.PERMANENT) {
                Log.w(TAG, "Permanent audio focus loss; stopping recognition capture")
                stopFromSystemInterrupt()
            }
        }

        // Ensure transcriber is initialized; surface a model-not-ready state instead of
        // failing silently when initialization does not complete.
        lifecycleScope.launch {
            Log.d(TAG, "Initializing transcriber...")
            val initialized =
                try {
                    transcriberProvider.initialize()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Transcriber initialization failed", e)
                    false
                }
            val ready = initialized && transcriberProvider.isReady()
            Log.d(TAG, "Transcriber ready: $ready")
            _modelState.value =
                if (ready) {
                    VoiceRecognitionModelState.Ready
                } else {
                    VoiceRecognitionModelState.Unavailable
                }
        }

        // IME-15/SEC-1: honor the caller's instructional prompt; PIPE-08: surface that the
        // engine is English-only when the caller asked for another language.
        val callerPrompt =
            intent?.getStringExtra(RecognizerIntent.EXTRA_PROMPT)?.takeIf { it.isNotBlank() }
        val englishOnlyHint =
            !isRecognitionLanguageSupported(intent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        val secure = secureSession

        setContent {
            val useDynamicColor by dynamicColorPreference.useDynamicColor
                .collectAsStateWithLifecycle(initialValue = DynamicColorPreference.DEFAULT_USE_DYNAMIC_COLOR)
            ChirpTheme(dynamicColor = useDynamicColor) {
                // PLH-8: the dialog's AI toggle is scoped to the keyboard-prefs llm_enabled
                // key — the same per-dictation scope the keyboard's pill uses — never the
                // global "Enable Processing" master switch in LLM Settings.
                val llmEnabled by keyboardPreferences.llmEnabled.collectAsStateWithLifecycle(initialValue = true)
                val currentMode by modePort.currentMode.collectAsStateWithLifecycle(initialValue = ProcessingMode.Proofread)
                // Selectable modes for the dialog's mode picker — the same source the keyboard's
                // mode selector reads (DLG-1/VIS-2). Defaults to empty so the dialog falls back to
                // the built-in mode list until the port emits.
                val selectableModes by modePort.selectableModes.collectAsStateWithLifecycle(initialValue = emptyList())
                // IME-6: secure sessions never run the cloud LLM path.
                val effectiveLlmEnabled = llmEnabled && !secure

                VoiceRecognitionDialog(
                    waveformBuffer = recorder.waveformBuffer,
                    sampleCountFlow = recorder.sampleCountFlow,
                    recordingStateFlow = _recordingState,
                    shouldDismissFlow = _shouldDismiss,
                    partialTranscriptFlow = _partialTranscript,
                    modelStateFlow = _modelState,
                    uiErrorFlow = _uiError,
                    llmEnabled = effectiveLlmEnabled,
                    aiControlEnabled = !secure,
                    currentMode = currentMode,
                    selectableModes = selectableModes,
                    callerPrompt = callerPrompt,
                    englishOnlyHint = englishOnlyHint,
                    onStart = ::startRecording,
                    onStop = { stopRecording(effectiveLlmEnabled, currentMode) },
                    onRetry = ::retryAfterNoSpeech,
                    onCancel = ::cancelRecording,
                    onOpenApp = ::openAppAndDismiss,
                    onDismissComplete = { finish() },
                    onToggleLlm = { enabled ->
                        lifecycleScope.launch {
                            keyboardPreferences.setLlmEnabled(enabled)
                        }
                    },
                    onModeChange = { modeId ->
                        lifecycleScope.launch {
                            modePort.setModeById(modeId)
                        }
                    },
                )
            }
        }
    }

    private fun startRecording() {
        if (_recordingState.value !is RecordingState.Idle) {
            Log.w(TAG, "Already recording, ignoring start request")
            return
        }
        if (_modelState.value != VoiceRecognitionModelState.Ready) {
            Log.w(TAG, "Transcription model not ready, ignoring start request")
            return
        }
        if (!RecordingPermissionGuard.hasRecordAudioPermission(this)) {
            Log.e(TAG, "Recording permission missing")
            // ERR-9: explain in-dialog with an affordance to open the app instead of
            // instantly vanishing; the error code is returned when the user dismisses.
            _uiError.value = VoiceRecognitionUiError.PermissionMissing
            return
        }
        // Reflect Starting in the dialog immediately; the coordinator's generation+mutex
        // (not this flag) is what serializes a rapid second tap against the in-flight start.
        _recordingState.value = RecordingState.Starting(RecordingOrigin.KEYBOARD)
        captureTeardownDiscardsAudio = false
        val generation = sessionCoordinator.issueGeneration()
        // Same initial-silence/trailing-silence detector the service uses (IME-2), honoring
        // the caller's RecognizerIntent silence extras. The dialog is a manual-stop surface,
        // so only the no-speech timeout is acted on (see onSessionAmplitude).
        val endpointer = recognizerIntentEndpointer(intent)
        lifecycleScope.launch {
            val result =
                sessionCoordinator.start(
                    generation = generation,
                    onReadyForSpeech = {},
                    onBeginningOfSpeech = {
                        _recordingState.value = RecordingState.Recording(RecordingOrigin.KEYBOARD)
                    },
                    onRms = { amplitude -> onSessionAmplitude(generation, endpointer, amplitude) },
                )
            when (result) {
                VoiceRecognitionSessionCoordinator.StartResult.Started -> Unit

                VoiceRecognitionSessionCoordinator.StartResult.Superseded -> {
                    // A newer start replaced this one before it ran; the newer session owns
                    // the recording state, so leave it untouched.
                    Log.w(TAG, "Start superseded before running")
                }

                VoiceRecognitionSessionCoordinator.StartResult.Cancelled -> {
                    // This activity gates start on model Ready synchronously and never marks a
                    // cancel, so this is not expected here; reset the dialog to Idle defensively
                    // rather than leaving it stuck on Starting.
                    Log.w(TAG, "Start cancelled before running")
                    _recordingState.value = RecordingState.Idle
                }

                is VoiceRecognitionSessionCoordinator.StartResult.Busy -> {
                    Log.w(TAG, "Microphone in use by ${result.sourceLabel}")
                    _recordingState.value = RecordingState.Idle
                    showErrorThenReturn(VoiceRecognitionUiError.MicBusy(result.sourceLabel))
                }

                is VoiceRecognitionSessionCoordinator.StartResult.Failed -> {
                    Log.e(TAG, "Failed to start recording", result.cause)
                    _recordingState.value = RecordingState.Idle
                    showErrorThenReturn(VoiceRecognitionUiError.CaptureFailed)
                }
            }
        }
    }

    /**
     * Per-amplitude-frame endpointer feed for the dialog session (IME-2 parity with the
     * service). END_OF_SPEECH is deliberately ignored — this surface keeps its explicit
     * stop button — but a session in which speech never starts must still terminate
     * instead of listening forever.
     */
    private fun onSessionAmplitude(
        generation: Int,
        endpointer: SpeechEndpointer,
        amplitude: Float,
    ) {
        when (endpointer.onAmplitude(amplitude, SystemClock.elapsedRealtime())) {
            SpeechEndpointer.Event.NO_SPEECH_TIMEOUT -> onNoSpeechTimeout(generation)
            else -> Unit
        }
    }

    /**
     * Initial-silence timeout: nothing was said within the budget, so cancel the capture
     * and show the gentle "didn't catch anything" retry state instead of either listening
     * forever or abruptly closing. The coordinator's generation gate keeps the terminal
     * exactly-once against a racing stop/cancel — if another path already ended the
     * session, it owns the outcome and this does nothing.
     */
    private fun onNoSpeechTimeout(generation: Int) {
        if (_shouldDismiss.value || _uiError.value != null) {
            return
        }
        // A timeout for a superseded session must not touch the live one's bookkeeping
        // (the discard mark below would wrongly strip the new session's rescue cover).
        if (generation != sessionCoordinator.currentGeneration()) {
            return
        }
        // The endpointer fires this only when no speech was ever detected, so the capture
        // is zero-speech audio: classify the teardown as a discard *before* the async
        // cancel so a destroy racing it never rescues pure silence (never-drop-speech
        // applies to speech; a session that raced into Stopping is owned by the pipeline).
        captureTeardownDiscardsAudio = true
        lifecycleScope.launch {
            if (!sessionCoordinator.cancel(generation)) {
                return@launch
            }
            Log.w(TAG, "No speech detected within the initial-silence budget; offering retry")
            _recordingState.value = RecordingState.Idle
            _uiError.value = VoiceRecognitionUiError.NoSpeechTimeout
        }
    }

    /** Retry from the no-speech state (W4 dialog UX): clear it and start a fresh session. */
    private fun retryAfterNoSpeech() {
        if (_shouldDismiss.value || _uiError.value != VoiceRecognitionUiError.NoSpeechTimeout) {
            return
        }
        _uiError.value = null
        startRecording()
    }

    private fun stopRecording(
        llmEnabled: Boolean,
        processingMode: ProcessingMode,
    ) {
        if (_recordingState.value !is RecordingState.Recording) {
            Log.w(TAG, "Not actively recording, ignoring stop request")
            return
        }
        val generation = sessionCoordinator.currentGeneration()
        val secure = secureSession
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Stop button pressed (LLM: $llmEnabled, Mode: ${processingMode.id})")
                _recordingState.value = RecordingState.Stopping(RecordingOrigin.KEYBOARD)

                // The coordinator serializes the stop against any in-flight start, stops the
                // recorder, and releases the capture gate; we own only what happens to the
                // captured samples afterwards.
                val samples =
                    when (val stop = sessionCoordinator.stop(generation) {}) {
                        is VoiceRecognitionSessionCoordinator.StopResult.Captured -> stop.samples
                        VoiceRecognitionSessionCoordinator.StopResult.Stale -> {
                            Log.w(TAG, "Stop ignored for inactive session")
                            _recordingState.value = RecordingState.Idle
                            returnError(SpeechRecognizer.ERROR_CLIENT)
                            return@launch
                        }
                        is VoiceRecognitionSessionCoordinator.StopResult.Failed -> {
                            Log.e(TAG, "Failed to stop recording", stop.cause)
                            _recordingState.value = RecordingState.Idle
                            showErrorThenReturn(VoiceRecognitionUiError.CaptureFailed)
                            return@launch
                        }
                    }
                Log.d(TAG, "Got ${samples.size} audio samples")

                if (samples.isEmpty()) {
                    showErrorThenReturn(VoiceRecognitionUiError.NoSpeech)
                    return@launch
                }

                // Guard the pipeline's persistence so a cancellation racing the final
                // persist cannot write a duplicate entry for the same capture. Secure
                // sessions persist nothing at all (IME-6).
                val persistence =
                    if (secure) {
                        SecureRecognitionCapturePersistence
                    } else {
                        DictationCapturePersistenceGuard(capturePersistence)
                    }
                var resultText = ""
                inlineTranscription.transcribe(
                    request =
                        InlineTranscriptionRequest.inMemory(
                            samples = samples,
                            llmEnabled = llmEnabled && !secure,
                            processingModeId = processingMode.id,
                            correlationPrefix = "voice",
                        ),
                    persistence = persistence,
                    commitText = { text ->
                        // IME-10: the pipeline's keyboard-oriented trailing space must not
                        // leak into the EXTRA_RESULTS payload.
                        resultText = text.trim()
                        _partialTranscript.value = text.trim()
                    },
                    onRecordingCompleted = {
                        _recordingState.value = RecordingState.Idle
                    },
                    onRecordingError = { message ->
                        Log.e(TAG, message)
                    },
                )

                when (val delivery = resolveRecognitionDelivery(resultText, inlineTranscription.phase.value)) {
                    is RecognitionDelivery.Success -> {
                        if (inlineTranscription.phase.value is InlineTranscriptionPhase.LlmError) {
                            Log.w(TAG, "LLM polish failed; returning raw transcript to caller")
                        }
                        // Never log transcript content: this dialog handles dictation for
                        // arbitrary apps. Log only its length (SLOP-7).
                        Log.d(TAG, "Returning result to caller (${delivery.text.length} chars)")
                        dismissWithResult(
                            resultCode = Activity.RESULT_OK,
                            data =
                                Intent().apply {
                                    putStringArrayListExtra(
                                        RecognizerIntent.EXTRA_RESULTS,
                                        arrayListOf(delivery.text),
                                    )
                                    // IME-15: single offline hypothesis, full confidence.
                                    putExtra(
                                        RecognizerIntent.EXTRA_CONFIDENCE_SCORES,
                                        floatArrayOf(1f),
                                    )
                                },
                        )
                    }

                    is RecognitionDelivery.Failure -> {
                        // ERR-27: the user already spoke — explain the failure (and that the
                        // audio was rescued) instead of silently closing the sheet.
                        val failurePhase = inlineTranscription.phase.value
                        val error =
                            if (failurePhase is InlineTranscriptionPhase.Error) {
                                VoiceRecognitionUiError.TranscriptionFailed(
                                    speechErrorCode = delivery.errorCode,
                                    // The inline pipeline rescues the capture on transcription
                                    // failure; secure sessions persist nothing by design.
                                    audioRescued = !secure,
                                )
                            } else {
                                VoiceRecognitionUiError.NoSpeech
                            }
                        showErrorThenReturn(error)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // The coordinator already released the capture gate when it stopped the
                // recorder above; failures here are in the inline transcription pipeline.
                Log.e(TAG, "Error during recognition", e)
                showErrorThenReturn(
                    VoiceRecognitionUiError.TranscriptionFailed(
                        speechErrorCode = SpeechRecognizer.ERROR_CLIENT,
                        audioRescued = false,
                    ),
                )
            }
        }
    }

    /**
     * Shared terminal path for system-initiated session ends (recorder cap, permanent
     * audio-focus loss): commit the capture exactly like a user stop, using the session's
     * effective AI settings.
     */
    private fun stopFromSystemInterrupt() {
        lifecycleScope.launch {
            val llmEnabled = !secureSession && keyboardPreferences.llmEnabled.first()
            val mode = modePort.currentMode.first()
            stopRecording(llmEnabled, mode)
        }
    }

    /**
     * Mid-capture recorder failure (IME-2): tear the session down and explain it in-dialog.
     * TooShort surfaces from the stop path itself and is handled there as an empty capture.
     */
    private fun onCaptureError(error: RecordingError) {
        if (error == RecordingError.TooShort) {
            return
        }
        lifecycleScope.launch {
            val state = _recordingState.value
            if (state !is RecordingState.Recording && state !is RecordingState.Starting) {
                return@launch
            }
            Log.e(TAG, "Recorder failed mid-session: ${error.userMessage}")
            sessionCoordinator.cancel(sessionCoordinator.currentGeneration())
            _recordingState.value = RecordingState.Idle
            showErrorThenReturn(VoiceRecognitionUiError.CaptureFailed)
        }
    }

    /**
     * Shows a terminal error inside the dialog for a beat, then dismisses with the mapped
     * RecognizerIntent result code — failures must be explained, never a silent close
     * (ERR-23/ERR-27). [VoiceRecognitionUiError.PermissionMissing] is persistent instead:
     * it carries an affordance and returns its error only when the user dismisses.
     */
    private fun showErrorThenReturn(error: VoiceRecognitionUiError) {
        _uiError.value = error
        lifecycleScope.launch {
            delay(ERROR_DISPLAY_MS)
            returnError(error.speechErrorCode)
        }
    }

    private fun cancelRecording() {
        // A result is already committed and the dismiss animation is in flight; a late
        // cancel must never downgrade it to RESULT_CANCELED.
        if (_shouldDismiss.value) {
            return
        }
        // A pending terminal error means the session already failed; dismissing now must
        // report that failure, not a misleading "user cancelled" (LIF-09).
        _uiError.value?.let { pendingError ->
            returnError(pendingError.speechErrorCode)
            return
        }
        if (_recordingState.value is RecordingState.Stopping) {
            // The inline pipeline is mid-transcription; finishing this activity cancels
            // it via lifecycleScope. Mark the cancellation as user-initiated so the
            // pipeline discards per the save preference instead of force-rescuing. An
            // unmarked cancellation (system kill, task swipe) still rescues the capture.
            inlineTranscription.markUserCancelled()
        }
        // The user chose to discard this capture: a destroy racing the async cancel below
        // (its recorder teardown hops to the IO dispatcher, so the gate can still be held
        // when onDestroy runs) must not re-file the discard as "Voice recognition
        // interrupted" (see shouldRescueOnDestroy).
        captureTeardownDiscardsAudio = true
        // Route the capture teardown through the coordinator so the cancel is serialized
        // against any in-flight start (it stops the recorder and releases the gate). When
        // the session is already past its active window (Stopping/Idle) cancel is a no-op,
        // and the pipeline / a prior stop already owns the teardown.
        val generation = sessionCoordinator.currentGeneration()
        lifecycleScope.launch {
            sessionCoordinator.cancel(generation)
        }
        _recordingState.value = RecordingState.Idle
        dismissWithResult(Activity.RESULT_CANCELED)
    }

    /**
     * ERR-9/ERR-10: "Open Chirp" affordance for the permission-missing and model-unavailable
     * states. Opens the app, then resolves the dialog: a pending error returns its mapped
     * code, otherwise the recognition is reported as cancelled.
     */
    private fun openAppAndDismiss() {
        runCatching {
            startActivity(
                Intent().setClassName(this, MAIN_ACTIVITY_CLASS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.onFailure { e -> Log.e(TAG, "Failed to open the app", e) }
        val pendingError = _uiError.value
        if (pendingError != null) {
            returnError(pendingError.speechErrorCode)
        } else {
            dismissWithResult(Activity.RESULT_CANCELED)
        }
    }

    override fun onDestroy() {
        if (captureGate.isHeld()) {
            val samples = recorder.stop()
            captureGate.releaseCompleted()
            if (shouldRescueOnDestroy(
                    recordingState = _recordingState.value,
                    secureSession = secureSession,
                    teardownDiscardsAudio = captureTeardownDiscardsAudio,
                )
            ) {
                rescueInterruptedCapture(samples)
            }
        }
        recorder.close()
        audioFocus.abandonFocus()
        super.onDestroy()
    }

    /**
     * The activity is being destroyed while a recognition session is still capturing
     * (e.g. the system killed the task mid-recording). Persist whatever audio was
     * captured so the recording can be recovered from history instead of being lost.
     */
    private fun rescueInterruptedCapture(samples: FloatArray) {
        if (samples.isEmpty()) {
            return
        }
        Log.w(TAG, "Rescuing ${samples.size} samples from interrupted recognition")
        rescueScope.launch {
            try {
                capturePersistence.persist(
                    samples = samples,
                    rawText = null,
                    processedText = null,
                    errorMessage = "Voice recognition interrupted",
                    // Not user-initiated: shouldRescueOnDestroy already excluded the
                    // teardowns the user (or the no-speech timeout) classified as a
                    // discard, so a held gate here means the system interrupted the
                    // capture mid-session.
                    reason = InlineCapturePersistReason.RESCUE,
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to rescue interrupted recognition audio", e)
            }
        }
    }

    /**
     * LIF-09/IME-9: failures are returned with the RecognizerIntent result code the
     * RECOGNIZE_SPEECH contract defines, so callers can tell no-match from audio failure
     * from server trouble. RESULT_CANCELED is reserved for genuine user cancels.
     */
    private fun returnError(errorCode: Int) {
        val resultCode = recognizerIntentResultCodeFor(errorCode)
        Log.w(TAG, "Returning result code $resultCode (speech error code: $errorCode)")
        val results =
            Intent().apply {
                putExtra(RecognizerIntent.EXTRA_RESULTS, ArrayList<String>())
            }
        dismissWithResult(resultCode, results)
    }

    private fun dismissWithResult(
        resultCode: Int,
        data: Intent? = null,
    ) {
        setResult(resultCode, data)
        Log.d(TAG, "Triggering dismiss animation")
        _shouldDismiss.value = true
    }

    companion object {
        private const val TAG = "VoiceRecognitionActivity"
        private const val ACTIVITY_AUDIO_PATH_LABEL = "voice_recognition_activity_temp_recording"
        private const val MAIN_ACTIVITY_CLASS = "dev.chirpboard.app.MainActivity"

        /** Material modal-scrim dim level for the host app behind the sheet (DLG-5/INS-2). */
        private const val DIALOG_DIM_AMOUNT = 0.32f

        /** How long a terminal in-dialog error stays readable before the result is returned. */
        private const val ERROR_DISPLAY_MS = 2_400L
    }
}

/**
 * IME-6: persistence used for [RecognizerIntent.EXTRA_SECURE] sessions — nothing is ever
 * written, including rescue artifacts: the caller explicitly demanded no storage, which
 * overrides the app's own keep-captured-speech preference for this session.
 */
internal object SecureRecognitionCapturePersistence : InlineCapturePersistence {
    override suspend fun persist(
        samples: FloatArray?,
        rawText: String?,
        processedText: String?,
        errorMessage: String?,
        reason: InlineCapturePersistReason,
    ) = Unit

    override suspend fun persistAudioSource(
        audioSource: InlineAudioSource?,
        rawText: String?,
        processedText: String?,
        errorMessage: String?,
        reason: InlineCapturePersistReason,
    ) = Unit

    override fun discardSamples() = Unit

    override fun discardAudioSource(audioSource: InlineAudioSource) = Unit
}

/** Outcome the activity returns to the calling app after the inline pipeline finishes. */
internal sealed interface RecognitionDelivery {
    data class Success(val text: String) : RecognitionDelivery

    data class Failure(val errorCode: Int) : RecognitionDelivery
}

/**
 * Decides what to return to the host app once the inline transcription pipeline settles.
 *
 * The committed text is the source of truth: if the pipeline committed anything, deliver
 * it. A failed LLM polish leaves the phase at [InlineTranscriptionPhase.LlmError] but
 * still commits the raw transcript (exactly as the keyboard surface does), so a polish
 * failure must never discard the user's words. Only a genuine transcription failure
 * (phase [InlineTranscriptionPhase.Error] with nothing committed) or an empty result
 * surfaces an error to the caller.
 */
internal fun resolveRecognitionDelivery(
    committedText: String,
    terminalPhase: InlineTranscriptionPhase,
): RecognitionDelivery =
    when {
        committedText.isNotBlank() -> RecognitionDelivery.Success(committedText)
        terminalPhase is InlineTranscriptionPhase.Error -> RecognitionDelivery.Failure(SpeechRecognizer.ERROR_CLIENT)
        else -> RecognitionDelivery.Failure(SpeechRecognizer.ERROR_NO_MATCH)
    }

/**
 * Whether a capture still held at activity destroy must be rescue-persisted.
 *
 * Rescue exists for *system* interruptions of a live capture (never-drop-speech):
 *  - once the samples are handed to the inline pipeline (Stopping) the pipeline owns
 *    persistence — rescuing here too would duplicate the capture;
 *  - secure sessions persist nothing, ever (IME-6);
 *  - a teardown the user (cancel) or the dialog itself (no-speech timeout, which by
 *    construction means zero detected speech) already classified as a discard must not
 *    be re-filed as "Voice recognition interrupted" merely because the asynchronous
 *    coordinator cancel had not yet released the gate when destroy ran.
 */
internal fun shouldRescueOnDestroy(
    recordingState: RecordingState,
    secureSession: Boolean,
    teardownDiscardsAudio: Boolean,
): Boolean =
    recordingState !is RecordingState.Stopping &&
        !secureSession &&
        !teardownDiscardsAudio

/**
 * Wraps the shared capture persistence for a single dictation hand-off to the inline
 * pipeline. The pipeline's cancellation rescue can fire after a terminal persist has
 * already completed (the persist finishes, then the cancellation surfaces on leaving
 * the dispatcher boundary and persists the same audio again as "Dictation cancelled"),
 * which would duplicate the entry.
 *
 * A follow-up persist is suppressed only when it is provably redundant:
 *  - after a rescue persist has completed — rescue persists always write an entry,
 *    so a second persist of the same capture would be a pure duplicate;
 *  - a user-cancel persist after a completed success persist — with saving enabled
 *    the capture is already stored, and with saving disabled the user-cancel persist
 *    would be dropped by the preference anyway.
 *
 * Anything else (notably a rescue after a success persist) is forwarded: a success
 * persist may have been skipped per user preference, so a forced rescue can be the
 * only surviving copy of an interrupted dictation. Suppressing on that uncertainty
 * would trade a duplicate entry for data loss, and discards must fail closed.
 */
internal class DictationCapturePersistenceGuard(
    private val delegate: InlineCapturePersistence,
) : InlineCapturePersistence {
    @Volatile
    private var rescuePersisted = false

    @Volatile
    private var successPersisted = false

    override fun prepareAudioSource(audioSource: InlineAudioSource) = delegate.prepareAudioSource(audioSource)

    override fun releasePendingAudioSource() = delegate.releasePendingAudioSource()

    override suspend fun persist(
        samples: FloatArray?,
        rawText: String?,
        processedText: String?,
        errorMessage: String?,
        reason: InlineCapturePersistReason,
    ) {
        if (isRedundant(reason)) {
            return
        }
        delegate.persist(samples, rawText, processedText, errorMessage, reason)
        recordCompletedPersist(reason)
    }

    override suspend fun persistAudioSource(
        audioSource: InlineAudioSource?,
        rawText: String?,
        processedText: String?,
        errorMessage: String?,
        reason: InlineCapturePersistReason,
    ) {
        if (isRedundant(reason)) {
            return
        }
        delegate.persistAudioSource(audioSource, rawText, processedText, errorMessage, reason)
        recordCompletedPersist(reason)
    }

    override fun discardSamples() = delegate.discardSamples()

    override fun discardAudioSource(audioSource: InlineAudioSource) = delegate.discardAudioSource(audioSource)

    private fun isRedundant(reason: InlineCapturePersistReason): Boolean =
        rescuePersisted || (successPersisted && reason == InlineCapturePersistReason.USER_CANCELLED)

    private fun recordCompletedPersist(reason: InlineCapturePersistReason) {
        when (reason) {
            InlineCapturePersistReason.RESCUE -> rescuePersisted = true
            InlineCapturePersistReason.COMPLETED -> successPersisted = true
            InlineCapturePersistReason.USER_CANCELLED -> Unit
        }
    }
}
