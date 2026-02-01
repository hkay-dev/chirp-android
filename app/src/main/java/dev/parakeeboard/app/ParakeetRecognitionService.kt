package dev.parakeeboard.app

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ParakeetRecognitionService : RecognitionService() {
    companion object {
        private const val TAG = "ParakeetRecognition"
        
        // Error codes matching android.speech.SpeechRecognizer
        private const val ERROR_AUDIO = 3
        private const val ERROR_RECOGNIZER_BUSY = 7
    }

    private val recorder = VoiceRecorder()
    private lateinit var recognizer: SherpaRecognizer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var recordingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        recognizer = SherpaRecognizer(this)
    }

    override fun onStartListening(intent: Intent, listener: Callback) {
        Log.d(TAG, "onStartListening")
        
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
                if (!recognizer.isReady) {
                    Log.w(TAG, "Recognizer not ready")
                    listener.error(ERROR_RECOGNIZER_BUSY)
                    return@launch
                }

                // Transcribe
                val text = recognizer.transcribe(samples)
                Log.d(TAG, "Transcribed: $text")

                if (text.isBlank()) {
                    Log.w(TAG, "Empty transcription")
                    listener.error(ERROR_AUDIO)
                    return@launch
                }

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
