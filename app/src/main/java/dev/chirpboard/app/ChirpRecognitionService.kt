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
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.recording.RecordingPermissionGuard
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
import dev.chirpboard.app.recognition.persistRecognitionHistoryAtomically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@AndroidEntryPoint
class ChirpRecognitionService : RecognitionService() {
    companion object {
        private const val TAG = "ChirpRecognition"

        /**
         * Upper bound the first recognition request waits for an in-flight model load
         * before surfacing ERROR_SERVER. Generous enough to cover the int8 encoder load
         * yet bounded so a stuck initialization cannot hang the client indefinitely.
         */
        private const val MODEL_READY_DEADLINE_MS = 10_000L
    }

    private val recorder by lazy { VoiceRecorder(this, scope, inputDeviceSelector) }
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
    lateinit var recordingRepository: RecordingRepository

    @Inject
    lateinit var inputDeviceSelector: AudioInputDeviceSelector

    @Inject
    lateinit var audioSettingsStore: AudioSettingsStore

    @Inject
    lateinit var recordingStateManager: RecordingStateManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

        // Initialize transcriber
        scope.launch {
            Log.d(TAG, "Initializing transcriber...")
            transcriberProvider.initialize()
            Log.d(TAG, "Transcriber ready: ${transcriberProvider.isReady()}")
        }
    }

    /**
     * Suspends until the recognizer reports ready or the deadline elapses. Re-invokes
     * [TranscriberProvider.initialize], which the underlying RecognizerManager serializes
     * behind a mutex — a concurrent call joins the in-flight load rather than starting a
     * second one. Returns true only if the model is ready within [MODEL_READY_DEADLINE_MS].
     */
    private suspend fun awaitModelReady(): Boolean =
        withTimeoutOrNull(MODEL_READY_DEADLINE_MS) {
            transcriberProvider.initialize() && transcriberProvider.isReady()
        } ?: false

    private fun saveTranscription(rawText: String) {
        scope.launch(Dispatchers.IO) {
            val persistenceResult =
                persistRecognitionHistoryAtomically(
                    rawText = rawText,
                    fallbackTitle = getString(R.string.recognition_history_fallback_title),
                ) { recording, transcript ->
                    recordingRepository.createRecordingWithTranscript(recording, transcript)
                }

            if (persistenceResult.isSuccess) {
                val recordingId = persistenceResult.getOrNull()
                Log.d(TAG, "Saved transcription atomically: recording=$recordingId")
            } else {
                val error = persistenceResult.exceptionOrNull()
                Log.e(TAG, "Failed to save transcription atomically", error)
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

        val generation = sessionCoordinator.issueGeneration()
        scope.launch {
            // The recognizer is loaded eagerly in onCreate, but a client that binds the
            // service and immediately starts listening can race that multi-second load.
            // Await the in-flight initialization (which RecognizerManager serializes) with
            // a deadline instead of failing the first request outright; only error when the
            // model genuinely failed or did not become ready within the deadline.
            if (!transcriberProvider.isReady() && !awaitModelReady()) {
                Log.w(TAG, "Recognizer not ready within deadline (model still loading)")
                // The client can cancel while we await the multi-second model load; consume
                // the mark so the cancel is its own terminal event and a stale ERROR_SERVER is
                // not delivered afterwards (and no later branch re-reads the mark).
                if (sessionCoordinator.consumeCancelRequest(generation)) {
                    Log.w(TAG, "Dropping not-ready error for cancelled session (generation=$generation)")
                } else {
                    runCatching { listener.error(SpeechRecognizer.ERROR_SERVER) }
                }
                return@launch
            }

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
                    onReadyForSpeech = { runCatching { listener.readyForSpeech(Bundle()) } },
                    // IME-20: beginningOfSpeech reflects *detected* speech, driven by the
                    // endpointer below — not the recorder merely starting.
                    onBeginningOfSpeech = {},
                    onRms = { amplitude ->
                        onSessionAmplitude(generation, listener, endpointer, secureSession, amplitude)
                    },
                )
            when (result) {
                VoiceRecognitionSessionCoordinator.StartResult.Started ->
                    armSessionTermination(generation, listener, secureSession, endpointer)

                VoiceRecognitionSessionCoordinator.StartResult.Cancelled ->
                    // The client cancelled while a slow model load delayed this start; the
                    // recorder/gate were never touched. The cancel was terminal, so no callback.
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
            scope.launch { stopAndDeliver(generation, listener, secureSession) }
        }
        recorder.onRecordingError = { error ->
            scope.launch { abortFailedSession(generation, listener, error) }
        }
        audioFocus.onFocusLost = { kind ->
            if (kind == AudioFocusManager.FocusLossKind.PERMANENT) {
                Log.w(TAG, "Permanent audio focus loss; stopping session (generation=$generation)")
                scope.launch { stopAndDeliver(generation, listener, secureSession) }
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
                scope.launch { stopAndDeliver(generation, listener, secureSession) }
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
        scope.launch { stopAndDeliver(generation, listener, secureSession) }
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
    ) {
        val result = sessionCoordinator.stop(generation) { runCatching { listener.endOfSpeech() } }
        when (result) {
            VoiceRecognitionSessionCoordinator.StopResult.Stale ->
                Log.w(TAG, "Ignoring stop for inactive session (generation=$generation)")

            is VoiceRecognitionSessionCoordinator.StopResult.Failed -> {
                Log.e(TAG, "Failed to stop recognition capture", result.cause)
                runCatching { listener.error(SpeechRecognizer.ERROR_AUDIO) }
            }

            is VoiceRecognitionSessionCoordinator.StopResult.Captured -> {
                // The session reached its terminal; its stall watchdog stands down (a
                // stale firing would be generation-gated anyway).
                cancelCaptureStallWatchdog()
                transcribeAndDeliver(result.samples, listener, secureSession)
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

    /**
     * Terminal path for a mid-capture recorder failure (IME-2): tear the session down and
     * report the mapped error. A session already past its active window is left alone —
     * its own stop path owns the terminal callback (e.g. TooShort surfacing from stop()).
     */
    private suspend fun abortFailedSession(
        generation: Int,
        listener: Callback,
        error: RecordingError,
    ) {
        if (sessionCoordinator.cancel(generation)) {
            cancelCaptureStallWatchdog()
            Log.e(TAG, "Recorder failed mid-session (generation=$generation): ${error.userMessage}")
            runCatching { listener.error(recognitionErrorCodeFor(error)) }
        }
    }

    /** Terminal path when no speech was ever detected within the timeout (IME-2). */
    private suspend fun abortSilentSession(
        generation: Int,
        listener: Callback,
    ) {
        if (sessionCoordinator.cancel(generation)) {
            Log.w(TAG, "No speech detected within timeout (generation=$generation)")
            runCatching { listener.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }
        }
    }

    private suspend fun transcribeAndDeliver(
        samples: FloatArray,
        listener: Callback,
        secureSession: Boolean,
    ) {
        try {
            // Empty capture -> benign NO_MATCH, not-ready -> SERVER, failed outcomes map per
            // the platform contract (IME-7). The decision is the extracted, unit-tested
            // resolveServiceRecognitionDelivery (TST-005).
            val delivery =
                resolveServiceRecognitionDelivery(
                    samplesEmpty = samples.isEmpty(),
                    recognizerReady = transcriberProvider.isReady(),
                ) { transcriberProvider.transcribe(samples) }
            if (delivery is ServiceRecognitionDelivery.Error) {
                Log.w(TAG, "Recognition not delivered (${delivery.logReason})")
                listener.error(delivery.errorCode)
                return
            }
            val text = (delivery as ServiceRecognitionDelivery.Results).text

            // Never log transcript content: this is an IME-adjacent surface and the text
            // can include anything the user dictates into other apps. Log only its length.
            Log.d(TAG, "Transcribed ${text.length} chars")

            // Save to history using data module — unless the caller marked the session
            // secure (IME-6), in which case nothing may be persisted.
            if (secureSession) {
                Log.d(TAG, "Secure session: skipping recognition history persistence")
            } else {
                saveTranscription(text)
            }

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
        val generation = sessionCoordinator.currentGeneration()
        // Record the cancel synchronously, before any queued start coroutine for this
        // generation resolves as Superseded: a session its own client cancelled must
        // not receive a stale terminal BUSY afterwards.
        sessionCoordinator.markCancelRequested(generation)
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
        val supported = listOf(SUPPORTED_RECOGNITION_LANGUAGE_TAG)
        val installed = if (transcriberProvider.isModelDownloaded()) supported else emptyList()
        val support =
            RecognitionSupport.Builder()
                .setSupportedOnDeviceLanguages(supported)
                .setInstalledOnDeviceLanguages(installed)
                .build()
        supportCallback.onSupportResult(support)
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
        when {
            !isRecognitionLanguageSupported(requestedLanguage) -> {
                Log.w(TAG, "Model download requested for unsupported language: $requestedLanguage")
                listener.onError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
            }

            transcriberProvider.isModelDownloaded() -> listener.onSuccess()

            else -> {
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
        // MIC-015/PERF-5: the coordinator shutdown cancels the recorder (AudioRecord
        // stop/release binder calls plus a temp-file delete) and close() releases its
        // buffers — blocking work that must not stall the main thread during destroy.
        // It hops to a short-lived IO scope that outlives [scope]; the session
        // bookkeeping the shutdown clears is never consulted again after destroy.
        // scope.cancel() stays ordered after the hop is launched, so in-flight session
        // jobs tear down exactly as before.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            sessionCoordinator.shutdown()
            recorder.close()
            audioFocus.abandonFocus()
        }
        scope.cancel()
        super.onDestroy()
    }
}
