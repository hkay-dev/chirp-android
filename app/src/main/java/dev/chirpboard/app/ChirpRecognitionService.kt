package dev.chirpboard.app

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
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

        // Error codes matching android.speech.SpeechRecognizer
        private const val ERROR_AUDIO = 3
        private const val ERROR_SERVER = 4  // Model not ready
        private const val ERROR_RECOGNIZER_BUSY = 7
    }

    private val recorder = VoiceRecorder()
    private var recognizer: SherpaRecognizer? = null
    
    @Inject
    lateinit var recordingRepository: RecordingRepository
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var recordingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // Eagerly initialize recognizer via singleton manager
        scope.launch {
            Log.d(TAG, "Loading recognizer singleton...")
            recognizer = RecognizerManager.getRecognizer(applicationContext)
            Log.d(TAG, "Recognizer loaded, ready: ${recognizer?.isReady}")
        }
    }

    private fun saveTranscription(rawText: String) {
        scope.launch(Dispatchers.IO) {
            try {
                // Create a Recording entity with KEYBOARD source (recognition service is used by keyboard)
                val recording = Recording(
                    title = rawText.take(50).ifBlank { "Voice transcription" },
                    audioPath = "", // No audio file saved for recognition service
                    source = RecordingSource.KEYBOARD,
                    status = RecordingStatus.COMPLETED
                )
                recordingRepository.insert(recording)
                
                // Create the linked Transcript
                val transcript = Transcript(
                    recordingId = recording.id,
                    rawText = rawText
                )
                recordingRepository.saveTranscript(transcript)
                
                Log.d(TAG, "Saved transcription: recording=${recording.id}, text='$rawText'")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save transcription", e)
            }
        }
    }

    override fun onStartListening(intent: Intent, listener: Callback) {
        Log.d(TAG, "onStartListening")

        // Check if model is ready before allowing recording
        val rec = recognizer
        if (rec == null || !rec.isReady) {
            Log.w(TAG, "Recognizer not ready yet (model still loading)")
            listener.error(ERROR_SERVER)
            return
        }

        // Check if already recording
        if (recorder.isRecording()) {
            Log.w(TAG, "Recorder already busy")
            listener.error(ERROR_RECOGNIZER_BUSY)
            return
        }

        scope.launch {
            try {
                // Notify ready
                listener.readyForSpeech(Bundle())

                // Start recording
                if (!recorder.start()) {
                    Log.e(TAG, "Failed to start recording")
                    listener.error(ERROR_AUDIO)
                    return@launch
                }

                listener.beginningOfSpeech()

                // Collect samples in background
                recordingJob = scope.launch {
                    try {
                        recorder.collectSamples()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error collecting samples", e)
                    }
                }

                // Monitor amplitudes for RMS reporting
                scope.launch {
                    recorder.amplitudes.collect { amps ->
                        if (amps.isNotEmpty()) {
                            val rms = amps.average() * 100  // Scale 0-100
                            listener.rmsChanged(rms.toFloat())
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in onStartListening", e)
                listener.error(ERROR_AUDIO)
            }
        }
    }

    override fun onStopListening(listener: Callback) {
        Log.d(TAG, "onStopListening")
        
        scope.launch {
            try {
                recordingJob?.cancel()
                val samples = recorder.stop()
                listener.endOfSpeech()

                if (samples.isEmpty()) {
                    Log.w(TAG, "No audio samples")
                    listener.error(ERROR_AUDIO)
                    return@launch
                }

                // Check if recognizer is ready
                val rec = recognizer
                if (rec == null || !rec.isReady) {
                    Log.w(TAG, "Recognizer not ready")
                    listener.error(ERROR_SERVER)
                    return@launch
                }

                // Transcribe
                val text = rec.transcribe(samples)
                Log.d(TAG, "Transcribed: $text")

                if (text.isBlank()) {
                    Log.w(TAG, "Empty transcription")
                    listener.error(ERROR_AUDIO)
                    return@launch
                }

                // Save to history using data module
                saveTranscription(text)

                // Send results
                val results = Bundle().apply {
                    putStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION,
                        arrayListOf(text)
                    )
                }
                listener.results(results)

            } catch (e: Exception) {
                Log.e(TAG, "Error in onStopListening", e)
                listener.error(ERROR_AUDIO)
            }
        }
    }

    override fun onCancel(listener: Callback) {
        Log.d(TAG, "onCancel")
        recordingJob?.cancel()
        recorder.stop()
        // Don't call listener - cancelled means no results
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        recordingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
