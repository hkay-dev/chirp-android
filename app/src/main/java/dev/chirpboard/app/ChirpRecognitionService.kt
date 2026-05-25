package dev.chirpboard.app

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.recording.RecordingPermissionGuard
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.keyboard.recorder.VoiceRecorder
import dev.chirpboard.app.recognition.persistRecognitionHistoryAtomically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ChirpRecognitionService : RecognitionService() {
    companion object {
        private const val TAG = "ChirpRecognition"
    }

    private val recorder by lazy { VoiceRecorder(this, scope) }

    @Inject
    lateinit var transcriberProvider: TranscriberProvider

    @Inject
    lateinit var recordingRepository: RecordingRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var recordingJob: Job? = null
    private var amplitudesJob: Job? = null

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

        // Check if already recording
        if (recorder.isRecording()) {
            Log.w(TAG, "Recorder already busy")
            listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }

        if (!RecordingPermissionGuard.hasRecordAudioPermission(this)) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            listener.error(SpeechRecognizer.ERROR_CLIENT)
            return
        }

        scope.launch {
            try {
                // Notify ready
                listener.readyForSpeech(Bundle())

                // Start recording
                if (!recorder.start()) {
                    Log.e(TAG, "Failed to start recording")
                    listener.error(SpeechRecognizer.ERROR_AUDIO)
                    return@launch
                }

                listener.beginningOfSpeech()

                // Collect samples in background
                recordingJob =
                    scope.launch {
                        try {
                            recorder.collectSamples()
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.e(TAG, "Error collecting samples", e)
                        }
                    }

                // Monitor amplitudes for RMS reporting
                amplitudesJob =
                    scope.launch {
                        recorder.sampleCountFlow.collect { count ->
                            if (count > 0L) {
                                val amp = recorder.waveformBuffer.lastOrNull() ?: 0f
                                val rms = amp * 100f // Scale 0-100
                                listener.rmsChanged(rms)
                            }
                        }
                    }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Error in onStartListening", e)
                listener.error(SpeechRecognizer.ERROR_AUDIO)
            }
        }
    }

    override fun onStopListening(listener: Callback) {
        Log.d(TAG, "onStopListening")

        scope.launch {
            try {
                amplitudesJob?.cancel()
                recordingJob?.cancel()
                val samples = recorder.stop()
                listener.endOfSpeech()

                if (samples.isEmpty()) {
                    Log.w(TAG, "No audio samples")
                    listener.error(SpeechRecognizer.ERROR_AUDIO)
                    return@launch
                }

                // Check if recognizer is ready
                if (!transcriberProvider.isReady()) {
                    Log.w(TAG, "Recognizer not ready")
                    listener.error(SpeechRecognizer.ERROR_SERVER)
                    return@launch
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
                            return@launch
                        }

                        is TranscriptionOutcome.ModelUnavailable -> {
                            Log.w(TAG, "Model unavailable: ${outcome.reason}")
                            listener.error(SpeechRecognizer.ERROR_SERVER)
                            return@launch
                        }

                        is TranscriptionOutcome.EngineError -> {
                            Log.e(TAG, "Engine error: ${outcome.reason}")
                            listener.error(SpeechRecognizer.ERROR_AUDIO)
                            return@launch
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
                Log.e(TAG, "Error in onStopListening", e)
                listener.error(SpeechRecognizer.ERROR_AUDIO)
            }
        }
    }

    override fun onCancel(listener: Callback) {
        Log.d(TAG, "onCancel")
        amplitudesJob?.cancel()
        recordingJob?.cancel()
        recorder.stop()
        // Don't call listener - cancelled means no results
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        amplitudesJob?.cancel()
        recordingJob?.cancel()
        recorder.close()
        scope.cancel()
        super.onDestroy()
    }
}
