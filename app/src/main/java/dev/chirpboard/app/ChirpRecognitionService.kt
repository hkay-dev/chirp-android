package dev.chirpboard.app

import android.content.AttributionSource
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.ModelDownloadListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.audio.AudioFocusManager
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.AudioSettingsStore
import dev.chirpboard.app.core.audio.recorder.RecordingError
import dev.chirpboard.app.core.audio.recorder.VoiceRecorder.CaptureStorageMode
import dev.chirpboard.app.core.llm.ProcessingMode
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.recording.RecordingPermissionGuard
import dev.chirpboard.app.core.transcription.InlineAudioSource
import dev.chirpboard.app.core.transcription.InlineCapturePersistReason
import dev.chirpboard.app.core.transcription.InlineCapturePersistence
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@AndroidEntryPoint
class ChirpRecognitionService : RecognitionService() {
    companion object {
        private const val TAG = "ChirpRecognition"
        private const val NO_STOP_GENERATION = -1
    }

    private val recorder by lazy {
        VoiceRecorder(
            context = this,
            coroutineScope = scope,
            inputDeviceSelector = inputDeviceSelector,
            captureStorageMode = CaptureStorageMode.FileBacked,
        )
    }
    private val captureGate by lazy { VoiceRecognitionCaptureGate(recordingStateManager) }
    private val sessionCoordinator by lazy {
        VoiceRecognitionSessionCoordinator(scope, captureGate, recorderControl)
    }

    /**
     * Transient-exclusive audio focus for the capture session (AUD-14): pauses other
     * playback so music is not blasted into the microphone, matching the keyboard and
     * RecordingService capture surfaces. A denied request degrades gracefully — capture
     * proceeds without focus.
     */
    private val audioFocus by lazy { AudioFocusManager(getSystemService(AudioManager::class.java)) }

    @Inject
    lateinit var transcriberProvider: TranscriberProvider

    @Inject
    lateinit var capturePersistence: InlineCapturePersistence

    @Inject
    lateinit var transcriptionRunner: VoiceRecognitionTranscriptionRunner

    @Inject
    lateinit var inputDeviceSelector: AudioInputDeviceSelector

    @Inject
    lateinit var audioSettingsStore: AudioSettingsStore

    @Inject
    lateinit var recordingStateManager: RecordingStateManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val rescueScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Whether the most recently issued session carried [RecognizerIntent.EXTRA_SECURE].
     * Read/written only on the service main thread, alongside the generation tokens.
     */
    private var currentSessionSecure = false

    /**
     * MIC-018: wall-clock frame-starvation watchdog for the live session, armed by
     * [armSessionTermination] and cancelled on the session's terminal. The endpointer is
     * event-driven — a capture whose reads stall entirely (wedged Bluetooth route, HAL
     * stall) feeds it nothing — so without this the client would wait forever for a
     * terminal callback. Read/written only on the service main thread, like
     * [currentSessionSecure].
     */
    private var captureStallWatchdog: Job? = null
    private var captureCheckpointJob: Job? = null
    private val captureStopGeneration = AtomicInteger(NO_STOP_GENERATION)
    private var captureTeardownDiscardsAudio = false
    private var serviceDestroyed = false
    private var activeTranscription: ActiveTranscription? = null

    private val recorderControl =
        object : VoiceRecognitionSessionCoordinator.RecorderControl {
            override suspend fun prepare() {
                recorder.gainMultiplier = audioSettingsStore.currentMicrophoneGain()
                if (audioFocus.requestFocus() == AudioFocusManager.FocusResult.Denied) {
                    Log.w(TAG, "Audio focus denied; capturing without focus")
                }
            }

            override suspend fun start(): Boolean = recorder.start()

            override fun stop(): InlineAudioSource {
                val audioSource =
                    recorder.stopToFileBacked()?.let { capture ->
                        InlineAudioSource.PcmFloatFile(
                            path = capture.file.absolutePath,
                            sampleCount = capture.sampleCount.toLong(),
                            sampleRate = capture.sampleRate,
                        )
                    } ?: InlineAudioSource.InMemory(FloatArray(0))
                audioFocus.abandonFocus()
                return audioSource
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

            override suspend fun streamRms(onRms: (Float) -> Unit) {
                recorder.sampleCountFlow.collect { count ->
                    if (count > 0L) {
                        val amp = recorder.waveformBuffer.lastOrNull() ?: 0f
                        onRms(amp)
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        scope.launch {
            try {
                Log.d(TAG, "Initializing transcriber...")
                transcriberProvider.initialize()
                Log.d(TAG, "Transcriber ready: ${transcriberProvider.isReady()}")
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Could not initialize the transcriber", error)
            }
        }
    }

    override fun onStartListening(
        intent: Intent,
        listener: Callback,
    ) {
        Log.d(TAG, "onStartListening")

        // IME-15: a caller requesting a non-English language must get
        // ERROR_LANGUAGE_NOT_SUPPORTED instead of silent English-model garbage.
        val requestedLanguage = recognitionRequestLanguageTag(intent)
        if (!isRecognitionLanguageSupported(requestedLanguage)) {
            Log.w(TAG, "Requested language not supported: $requestedLanguage")
            runCatching { listener.error(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) }
            return
        }

        if (!RecordingPermissionGuard.hasRecordAudioPermission(this)) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            // IME-7: the contract code for a missing RECORD_AUDIO grant, which drives
            // client permission UX (not ERROR_CLIENT).
            runCatching { listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
            return
        }

        // IME-6: EXTRA_SECURE sessions (keyguard/secure contexts) must not be persisted
        // into the recognition history.
        val secureSession = intent.getBooleanExtra(RecognizerIntent.EXTRA_SECURE, false)
        currentSessionSecure = secureSession
        captureTeardownDiscardsAudio = false

        val generation = sessionCoordinator.issueGeneration()
        scope.launch {
            // The per-session endpointer honors the caller's silence extras, with its
            // speech threshold compensated for the session's microphone gain (MIC-018):
            // the amplitude stream is post-gain, so steady amplified ambient noise would
            // otherwise establish "speech" and disable the no-speech cap.
            val endpointer =
                recognizerIntentEndpointer(intent)
                    .gainCompensated(audioSettingsStore.currentMicrophoneGain())
            val result =
                sessionCoordinator.start(
                    generation = generation,
                    // Readiness means the first microphone block reached durable storage, not
                    // merely that AudioRecord.startRecording returned.
                    onReadyForSpeech = {},
                    // IME-20: beginningOfSpeech reflects *detected* speech, driven by the
                    // endpointer below — not the recorder merely starting.
                    onBeginningOfSpeech = {},
                    onRms = { amplitude ->
                        onSessionAmplitude(generation, listener, endpointer, secureSession, amplitude)
                    },
                )
            when (result) {
                VoiceRecognitionSessionCoordinator.StartResult.Started -> {
                    if (!recorder.awaitFirstSamples()) {
                        if (sessionCoordinator.consumeCancelRequest(generation)) {
                            Log.w(TAG, "First-sample wait ended for cancelled session (generation=$generation)")
                            return@launch
                        }
                        sessionCoordinator.cancel(generation)
                        runCatching { listener.error(SpeechRecognizer.ERROR_AUDIO) }
                        return@launch
                    }
                    checkpointFirstRecognitionAudio()
                    runCatching { listener.readyForSpeech(Bundle()) }
                    armSessionTermination(generation, listener, secureSession, endpointer)
                }

                VoiceRecognitionSessionCoordinator.StartResult.Cancelled ->
                    // The client cancelled while this start was queued. The cancel was terminal,
                    // so no callback is owed and the microphone was never opened.
                    Log.w(TAG, "Start aborted: client cancelled before it ran (generation=$generation)")

                VoiceRecognitionSessionCoordinator.StartResult.Superseded -> {
                    Log.w(TAG, "Start superseded before running (generation=$generation)")
                    if (sessionCoordinator.consumeCancelRequest(generation)) {
                        // This session's own client already cancelled it, so the cancel was
                        // its terminal event. On API <= 32 the framework treats any
                        // listener.error() as terminal for the ACTIVE session; a stale BUSY
                        // delivered here could reset the newer session's bookkeeping.
                        Log.w(TAG, "Dropping BUSY for cancelled superseded session (generation=$generation)")
                    } else {
                        // The framework normally abandons the superseded session via cancel
                        // before issuing a new start, but a client that fires back-to-back
                        // starts must still get a terminal callback instead of hanging.
                        runCatching { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
                    }
                }

                is VoiceRecognitionSessionCoordinator.StartResult.Busy -> {
                    Log.w(TAG, "Microphone in use by ${result.sourceLabel}")
                    runCatching { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
                }

                is VoiceRecognitionSessionCoordinator.StartResult.Failed -> {
                    Log.e(TAG, "Failed to start recognition capture", result.cause)
                    runCatching { listener.error(SpeechRecognizer.ERROR_AUDIO) }
                }
            }
        }
    }

    /**
     * IME-2: every way a live session can end without an explicit client stop must still
     * produce a terminal callback — the 10-minute recorder cap, a mid-capture recorder
     * failure, a permanent audio-focus loss, and a frame-starved capture (MIC-018) all
     * route into the same generation-gated stop/cancel paths a manual stop uses, so a
     * racing manual stop stays idempotent. Armed only after
     * [VoiceRecognitionSessionCoordinator.StartResult.Started] so a Busy start can never
     * re-point the live session's callbacks at a rejected one.
     */
    private fun armSessionTermination(
        generation: Int,
        listener: Callback,
        secureSession: Boolean,
        endpointer: SpeechEndpointer,
    ) {
        recorder.onLimitReached = {
            Log.w(TAG, "Recording limit reached; delivering captured audio (generation=$generation)")
            requestStopAndDeliver(generation, listener, secureSession)
        }
        recorder.onRecordingError = { error ->
            if (error != RecordingError.TooShort) {
                Log.e(TAG, "Recorder failed mid-session (generation=$generation): ${error.userMessage}")
                requestStopAndDeliver(
                    generation = generation,
                    listener = listener,
                    secureSession = secureSession,
                    captureFailureMessage = error.userMessage,
                )
            }
        }
        audioFocus.onFocusLost = { kind ->
            if (kind == AudioFocusManager.FocusLossKind.PERMANENT) {
                Log.w(TAG, "Permanent audio focus loss; stopping session (generation=$generation)")
                requestStopAndDeliver(generation, listener, secureSession)
            }
        }
        // MIC-018: a capture whose reads stall delivers no amplitude frames, so the
        // event-driven endpointer can never time it out; the wall-clock watchdog routes
        // a stalled session into the same generation-gated no-speech path.
        captureStallWatchdog?.cancel()
        captureStallWatchdog =
            scope.launch {
                if (awaitRecognitionCaptureStall(endpointer) { recorder.sampleCountFlow.value }) {
                    Log.w(TAG, "Capture frames stalled; aborting session as no-speech (generation=$generation)")
                    abortSilentSession(generation, listener)
                }
            }
    }

    /**
     * Per-amplitude-frame handling: forwards a contract-range RMS dB value to the client
     * (IME-19) and drives the endpointer (IME-2/IME-20). Terminal endpointer events are
     * generation-gated, so a session the client already stopped is unaffected.
     */
    private fun onSessionAmplitude(
        generation: Int,
        listener: Callback,
        endpointer: SpeechEndpointer,
        secureSession: Boolean,
        amplitude: Float,
    ) {
        runCatching { listener.rmsChanged(amplitudeToRmsDb(amplitude)) }
        when (endpointer.onAmplitude(amplitude, SystemClock.elapsedRealtime())) {
            SpeechEndpointer.Event.SPEECH_STARTED ->
                runCatching { listener.beginningOfSpeech() }

            SpeechEndpointer.Event.END_OF_SPEECH -> {
                Log.d(TAG, "Trailing silence detected; ending session (generation=$generation)")
                requestStopAndDeliver(generation, listener, secureSession)
            }

            SpeechEndpointer.Event.NO_SPEECH_TIMEOUT ->
                scope.launch { abortSilentSession(generation, listener) }

            SpeechEndpointer.Event.NONE -> Unit
        }
    }

    override fun onStopListening(listener: Callback) {
        Log.d(TAG, "onStopListening")

        val generation = sessionCoordinator.currentGeneration()
        val secureSession = currentSessionSecure
        requestStopAndDeliver(generation, listener, secureSession)
    }

    private fun requestStopAndDeliver(
        generation: Int,
        listener: Callback,
        secureSession: Boolean,
        captureFailureMessage: String? = null,
    ) {
        if (!captureStopGeneration.compareAndSet(NO_STOP_GENERATION, generation)) {
            return
        }
        rescueScope.launch(Dispatchers.Main.immediate) {
            stopAndDeliver(
                generation = generation,
                listener = listener,
                secureSession = secureSession,
                captureFailureMessage = captureFailureMessage,
            )
        }
    }

    /**
     * Shared stop path for manual stops, end-of-speech, the recorder limit, and permanent
     * focus loss. The coordinator's generation token makes concurrent invocations safe:
     * exactly one resolves as Captured, the rest are stale no-ops.
     */
    private suspend fun stopAndDeliver(
        generation: Int,
        listener: Callback,
        secureSession: Boolean,
        captureFailureMessage: String? = null,
    ) {
        val result =
            try {
                cancelCaptureStallWatchdog()
                captureCheckpointJob?.join()
                captureCheckpointJob = null
                sessionCoordinator.stop(generation) { runCatching { listener.endOfSpeech() } }
            } finally {
                captureStopGeneration.compareAndSet(generation, NO_STOP_GENERATION)
            }
        if (serviceDestroyed) {
            recorder.close()
            audioFocus.abandonFocus()
        }
        when (result) {
            VoiceRecognitionSessionCoordinator.StopResult.Stale ->
                Log.w(TAG, "Ignoring stop for inactive session (generation=$generation)")

            is VoiceRecognitionSessionCoordinator.StopResult.Failed -> {
                Log.e(TAG, "Failed to stop recognition capture", result.cause)
                runCatching { listener.error(SpeechRecognizer.ERROR_AUDIO) }
            }

            is VoiceRecognitionSessionCoordinator.StopResult.Captured -> {
                transcribeAndDeliver(
                    generation = generation,
                    audioSource = result.audioSource,
                    listener = listener,
                    secureSession = secureSession,
                    captureFailureMessage = captureFailureMessage,
                )
            }
        }
    }

    /**
     * Stands the stall watchdog down on a session terminal. Never called from the
     * watchdog's own coroutine: the endpointer-driven terminals stand it down via the
     * endpointer's terminal mark, and a watchdog-driven firing has already completed.
     */
    private fun cancelCaptureStallWatchdog() {
        captureStallWatchdog?.cancel()
        captureStallWatchdog = null
    }

    /** Terminal path when no speech was ever detected within the timeout (IME-2). */
    private suspend fun abortSilentSession(
        generation: Int,
        listener: Callback,
    ) {
        if (generation != sessionCoordinator.currentGeneration()) {
            return
        }
        captureTeardownDiscardsAudio = true
        if (sessionCoordinator.cancel(generation)) {
            Log.w(TAG, "No speech detected within timeout (generation=$generation)")
            runCatching { listener.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }
        }
    }

    private suspend fun transcribeAndDeliver(
        generation: Int,
        audioSource: InlineAudioSource,
        listener: Callback,
        secureSession: Boolean,
        captureFailureMessage: String?,
    ) {
        try {
            if (audioSource.sampleCount() == 0L) {
                SecureRecognitionCapturePersistence.discardAudioSource(audioSource)
                runCatching { listener.error(SpeechRecognizer.ERROR_NO_MATCH) }
                return
            }

            val session =
                transcriptionRunner.start(
                    VoiceRecognitionTranscriptionRunner.Request(
                        audioSource = audioSource,
                        llmEnabled = false,
                        processingModeId = ProcessingMode.Proofread.id,
                        secure = secureSession,
                        captureFailureMessage = captureFailureMessage,
                    ),
                )
            val ownedSession = ActiveTranscription(generation, session)
            activeTranscription = ownedSession
            val outcome =
                try {
                    session.result.await()
                } finally {
                    if (activeTranscription === ownedSession) {
                        activeTranscription = null
                    }
                }
            val delivery =
                resolveServiceRecognitionDelivery(
                    committedText = outcome.committedText,
                    terminalPhase = outcome.terminalPhase,
                    recognizerReady = transcriberProvider.isReady(),
                )
            if (delivery is ServiceRecognitionDelivery.Error) {
                Log.w(TAG, "Recognition not delivered (${delivery.logReason})")
                runCatching { listener.error(delivery.errorCode) }
                return
            }
            val text = (delivery as ServiceRecognitionDelivery.Results).text

            // Never log transcript content: this is an IME-adjacent surface and the text
            // can include anything the user dictates into other apps. Log only its length.
            Log.d(TAG, "Transcribed ${text.length} chars")

            // Send results
            val results =
                Bundle().apply {
                    putStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION,
                        arrayListOf(text),
                    )
                    // IME-15: some clients index the confidence array; the offline decoder
                    // emits a single hypothesis, reported with full confidence.
                    putFloatArray(
                        SpeechRecognizer.CONFIDENCE_SCORES,
                        floatArrayOf(1f),
                    )
                }
            listener.results(results)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Error delivering recognition result", e)
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
        }
    }

    override fun onCancel(listener: Callback) {
        Log.d(TAG, "onCancel")
        captureTeardownDiscardsAudio = true
        val generation = sessionCoordinator.currentGeneration()
        // Record the cancel synchronously, before any queued start coroutine for this
        // generation resolves as Superseded: a session its own client cancelled must
        // not receive a stale terminal BUSY afterwards.
        sessionCoordinator.markCancelRequested(generation)
        activeTranscription
            ?.takeIf { it.generation == generation }
            ?.also { it.session.cancel(userInitiated = true) }
        activeTranscription = null
        // The client abandoned the session; its stall watchdog must not outlive it.
        cancelCaptureStallWatchdog()
        scope.launch {
            if (!sessionCoordinator.cancel(generation)) {
                Log.w(TAG, "Ignoring cancel for inactive session (generation=$generation)")
            }
            // Don't call listener - cancelled means no results
        }
    }

    /**
     * IME-15/PIPE-08: reports the engine's real language support so well-behaved callers
     * can discover that only English is available instead of feeding users silent
     * wrong-language output.
     */
    override fun onCheckRecognitionSupport(
        recognizerIntent: Intent,
        supportCallback: SupportCallback,
    ) {
        // isModelDownloaded re-hashes the full model (hundreds of MB of SHA-256) on a
        // verification-cache miss, and these callbacks arrive on the IME-shared main thread;
        // answer from a background dispatcher (rescueScope survives past onDestroy).
        rescueScope.launch {
            val supported = listOf(SUPPORTED_RECOGNITION_LANGUAGE_TAG)
            val installed = if (transcriberProvider.isModelDownloaded()) supported else emptyList()
            val support =
                RecognitionSupport.Builder()
                    .setSupportedOnDeviceLanguages(supported)
                    .setInstalledOnDeviceLanguages(installed)
                    .build()
            supportCallback.onSupportResult(support)
        }
    }

    /**
     * The 660MB Parakeet model can only be downloaded through the app UI (storage
     * permission + Wi-Fi sized download), never headlessly from a service callback; report
     * the truthful terminal state instead of pretending to schedule one (IME-15).
     */
    override fun onTriggerModelDownload(
        recognizerIntent: Intent,
        attributionSource: AttributionSource,
        listener: ModelDownloadListener,
    ) {
        val requestedLanguage = recognitionRequestLanguageTag(recognizerIntent)
        if (!isRecognitionLanguageSupported(requestedLanguage)) {
            Log.w(TAG, "Model download requested for unsupported language: $requestedLanguage")
            listener.onError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
            return
        }
        // Same as onCheckRecognitionSupport: keep the possible full-file hash off the main thread.
        rescueScope.launch {
            if (transcriberProvider.isModelDownloaded()) {
                listener.onSuccess()
            } else {
                Log.w(TAG, "Model download must be started from the Chirp app, not the recognition service")
                listener.onError(SpeechRecognizer.ERROR_SERVER)
            }
        }
    }

    /** The caller's requested language, if any (EXTRA_LANGUAGE wins over the preference). */
    private fun recognitionRequestLanguageTag(intent: Intent): String? =
        intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
            ?: intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        serviceDestroyed = true
        val gateHeld = captureGate.isHeld()
        val rescue = gateHeld && !currentSessionSecure && !captureTeardownDiscardsAudio
        rescueScope.launchRecognitionDestroyTeardown(
            stopOwnerActive = captureStopGeneration.get() != NO_STOP_GENERATION,
            gateHeld = gateHeld,
            rescue = rescue,
            stopRecorder = recorderControl::stop,
            releaseGate = captureGate::releaseCompleted,
            rescueSamples = ::rescueInterruptedCapture,
            closeRecorder = recorder::close,
            abandonFocus = audioFocus::abandonFocus,
        )
        scope.cancel()
        super.onDestroy()
    }

    /** Journals ownership after the first complete PCM block, off the microphone path. */
    private fun checkpointFirstRecognitionAudio() {
        val snapshot = recorder.activeFileBackedSnapshot() ?: return
        captureCheckpointJob =
            rescueScope.launch {
                try {
                    if (!checkpointRecognitionAudio(snapshot, capturePersistence)) {
                        Log.w(TAG, "Could not checkpoint the first recognition-service audio block")
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Could not checkpoint the first recognition-service audio block", error)
                }
            }
    }

    private fun rescueInterruptedCapture(audioSource: InlineAudioSource) {
        if (audioSource.sampleCount() == 0L) {
            return
        }
        rescueScope.launch {
            try {
                capturePersistence.persistAudioSource(
                    audioSource = audioSource,
                    rawText = null,
                    processedText = null,
                    errorMessage = "Voice recognition service interrupted",
                    reason = InlineCapturePersistReason.RESCUE,
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Failed to rescue interrupted recognition-service audio", error)
            }
        }
    }

    private data class ActiveTranscription(
        val generation: Int,
        val session: VoiceRecognitionTranscriptionRunner.Session,
    )
}
