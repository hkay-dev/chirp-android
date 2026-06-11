package dev.chirpboard.app

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.AudioSettingsStore
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.recording.RecordingPermissionGuard
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
import dev.chirpboard.app.recognition.persistRecognitionHistoryAtomically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ChirpRecognitionService : RecognitionService() {
    companion object {
        private const val TAG = "ChirpRecognition"
        private const val RMS_SCALE = 100f
    }

    private val recorder by lazy { VoiceRecorder(this, scope, inputDeviceSelector) }
    private val captureGate by lazy { VoiceRecognitionCaptureGate(recordingStateManager) }
    private val sessionCoordinator by lazy {
        VoiceRecognitionSessionCoordinator(scope, captureGate, recorderControl)
    }

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

    private val recorderControl =
        object : VoiceRecognitionSessionCoordinator.RecorderControl {
            override suspend fun prepare() {
                recorder.gainMultiplier = audioSettingsStore.currentMicrophoneGain()
            }

            override suspend fun start(): Boolean = recorder.start()

            override fun stop(): FloatArray = recorder.stop()

            override fun cancel() = recorder.cancelCapture()

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
                        onRms(amp * RMS_SCALE)
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

    private fun saveTranscription(rawText: String) {
        scope.launch(Dispatchers.IO) {
            val persistenceResult =
                persistRecognitionHistoryAtomically(rawText) { recording, transcript ->
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

        // Check if model is ready before allowing recording
        if (!transcriberProvider.isReady()) {
            Log.w(TAG, "Recognizer not ready yet (model still loading)")
            listener.error(SpeechRecognizer.ERROR_SERVER)
            return
        }

        if (!RecordingPermissionGuard.hasRecordAudioPermission(this)) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            listener.error(SpeechRecognizer.ERROR_CLIENT)
            return
        }

        val generation = sessionCoordinator.issueGeneration()
        scope.launch {
            val result =
                sessionCoordinator.start(
                    generation = generation,
                    onReadyForSpeech = { listener.readyForSpeech(Bundle()) },
                    onBeginningOfSpeech = { listener.beginningOfSpeech() },
                    onRms = { rms -> runCatching { listener.rmsChanged(rms) } },
                )
            when (result) {
                VoiceRecognitionSessionCoordinator.StartResult.Started -> Unit

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

    override fun onStopListening(listener: Callback) {
        Log.d(TAG, "onStopListening")

        val generation = sessionCoordinator.currentGeneration()
        scope.launch {
            val result = sessionCoordinator.stop(generation) { listener.endOfSpeech() }
            when (result) {
                VoiceRecognitionSessionCoordinator.StopResult.Stale ->
                    Log.w(TAG, "Ignoring stop for inactive session (generation=$generation)")

                is VoiceRecognitionSessionCoordinator.StopResult.Failed -> {
                    Log.e(TAG, "Failed to stop recognition capture", result.cause)
                    runCatching { listener.error(SpeechRecognizer.ERROR_AUDIO) }
                }

                is VoiceRecognitionSessionCoordinator.StopResult.Captured ->
                    transcribeAndDeliver(result.samples, listener)
            }
        }
    }

    private suspend fun transcribeAndDeliver(
        samples: FloatArray,
        listener: Callback,
    ) {
        try {
            if (samples.isEmpty()) {
                Log.w(TAG, "No audio samples")
                listener.error(SpeechRecognizer.ERROR_AUDIO)
                return
            }

            // Check if recognizer is ready
            if (!transcriberProvider.isReady()) {
                Log.w(TAG, "Recognizer not ready")
                listener.error(SpeechRecognizer.ERROR_SERVER)
                return
            }

            // Transcribe with typed outcome
            val outcome = transcriberProvider.transcribe(samples)
            val text =
                when (outcome) {
                    is TranscriptionOutcome.Success -> {
                        outcome.text
                    }

                    TranscriptionOutcome.NoSpeech -> {
                        Log.w(TAG, "No speech detected")
                        listener.error(SpeechRecognizer.ERROR_AUDIO)
                        return
                    }

                    is TranscriptionOutcome.ModelUnavailable -> {
                        Log.w(TAG, "Model unavailable: ${outcome.reason}")
                        listener.error(SpeechRecognizer.ERROR_SERVER)
                        return
                    }

                    is TranscriptionOutcome.EngineError -> {
                        Log.e(TAG, "Engine error: ${outcome.reason}")
                        listener.error(SpeechRecognizer.ERROR_AUDIO)
                        return
                    }
                }

            Log.d(TAG, "Transcribed: $text")

            // Save to history using data module
            saveTranscription(text)

            // Send results
            val results =
                Bundle().apply {
                    putStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION,
                        arrayListOf(text),
                    )
                }
            listener.results(results)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Error delivering recognition result", e)
            runCatching { listener.error(SpeechRecognizer.ERROR_AUDIO) }
        }
    }

    override fun onCancel(listener: Callback) {
        Log.d(TAG, "onCancel")
        val generation = sessionCoordinator.currentGeneration()
        // Record the cancel synchronously, before any queued start coroutine for this
        // generation resolves as Superseded: a session its own client cancelled must
        // not receive a stale terminal BUSY afterwards.
        sessionCoordinator.markCancelRequested(generation)
        scope.launch {
            if (!sessionCoordinator.cancel(generation)) {
                Log.w(TAG, "Ignoring cancel for inactive session (generation=$generation)")
            }
            // Don't call listener - cancelled means no results
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        sessionCoordinator.shutdown()
        recorder.close()
        scope.cancel()
        super.onDestroy()
    }
}
