package dev.chirpboard.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.AudioSettingsStore
import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
import dev.chirpboard.app.core.llm.ProcessingMode
import dev.chirpboard.app.core.llm.ProcessingModePort
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
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Minimal dialog-style activity for voice recognition (like Google's).
 * Handles android.speech.action.RECOGNIZE_SPEECH intents from other apps.
 */
@AndroidEntryPoint
class VoiceRecognitionActivity : ComponentActivity() {
    private val recorder by lazy { VoiceRecorder(this, lifecycleScope, inputDeviceSelector) }
    private val captureGate by lazy { VoiceRecognitionCaptureGate(recordingStateManager) }
    private var recordingJob: Job? = null

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

    @Inject lateinit var llmPreferences: LlmPreferences
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    private val _shouldDismiss = MutableStateFlow(false)
    private val _partialTranscript = MutableStateFlow("")
    private val _modelState = MutableStateFlow(VoiceRecognitionModelState.Initializing)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "VoiceRecognitionActivity created")

        androidx.core.view.WindowCompat
            .setDecorFitsSystemWindows(window, false)
        val params = window.attributes
        params.gravity = android.view.Gravity.BOTTOM
        params.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
        params.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
        // Don't dim the background and watch for outside touches to dismiss
        params.flags = params.flags or android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        params.flags = params.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
        window.attributes = params

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

        setContent {
            ChirpTheme {
                val llmEnabled by llmPreferences.llmEnabled.collectAsStateWithLifecycle(initialValue = true)
                val currentMode by modePort.currentMode.collectAsStateWithLifecycle(initialValue = ProcessingMode.Proofread)

                VoiceRecognitionDialog(
                    waveformBuffer = recorder.waveformBuffer,
                    sampleCountFlow = recorder.sampleCountFlow,
                    recordingStateFlow = _recordingState,
                    shouldDismissFlow = _shouldDismiss,
                    partialTranscriptFlow = _partialTranscript,
                    modelStateFlow = _modelState,
                    llmEnabled = llmEnabled,
                    currentMode = currentMode,
                    onStart = ::startRecording,
                    onStop = { stopRecording(llmEnabled, currentMode) },
                    onCancel = ::cancelRecording,
                    onDismissComplete = { finish() },
                    onToggleLlm = { enabled ->
                        lifecycleScope.launch {
                            llmPreferences.setLlmEnabled(enabled)
                        }
                    },
                )
            }
        }
    }

    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean {
        if (event?.action == android.view.MotionEvent.ACTION_OUTSIDE) {
            Log.d(TAG, "Touched outside, cancelling recording")
            cancelRecording()
            return true
        }
        return super.onTouchEvent(event)
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
            returnError(SpeechRecognizer.ERROR_AUDIO)
            return
        }
        // Mark the session as starting before any suspension so a rapid second tap
        // during AudioRecord init is ignored instead of tripping the non-reentrant
        // capture gate against this activity's own in-flight start.
        _recordingState.value = RecordingState.Starting(RecordingOrigin.KEYBOARD)
        lifecycleScope.launch {
            try {
                when (val result = captureGate.tryAcquire()) {
                    VoiceRecognitionCaptureGateResult.Acquired -> Unit
                    is VoiceRecognitionCaptureGateResult.Busy -> {
                        Log.w(TAG, "Microphone in use by ${result.sourceLabel}")
                        _recordingState.value = RecordingState.Idle
                        returnError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
                        return@launch
                    }
                }

                recorder.gainMultiplier = audioSettingsStore.currentMicrophoneGain()

                if (!recorder.start()) {
                    Log.e(TAG, "Failed to start recording")
                    captureGate.releaseError("Failed to start voice recognition")
                    _recordingState.value = RecordingState.Idle
                    returnError(SpeechRecognizer.ERROR_AUDIO)
                    return@launch
                }
                captureGate.onRecorderStarted("voice_recognition_activity_temp_recording")
                _recordingState.value = RecordingState.Recording(RecordingOrigin.KEYBOARD)

                // Collect samples in background
                recordingJob =
                    lifecycleScope.launch {
                        recorder.collectSamples()
                    }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Error starting recording", e)
                captureGate.releaseError("Failed to start voice recognition", e)
                _recordingState.value = RecordingState.Idle
                returnError(SpeechRecognizer.ERROR_AUDIO)
            }
        }
    }

    private fun stopRecording(
        llmEnabled: Boolean,
        processingMode: ProcessingMode,
    ) {
        if (_recordingState.value !is RecordingState.Recording) {
            Log.w(TAG, "Not actively recording, ignoring stop request")
            return
        }
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Stop button pressed (LLM: $llmEnabled, Mode: ${processingMode.id})")
                _recordingState.value = RecordingState.Stopping(RecordingOrigin.KEYBOARD)
                recordingJob?.cancel()
                val samples = recorder.stop()
                captureGate.releaseCompleted()
                Log.d(TAG, "Got ${samples.size} audio samples")

                if (samples.isEmpty()) {
                    returnError(SpeechRecognizer.ERROR_NO_MATCH)
                    return@launch
                }

                // Guard the pipeline's persistence so a cancellation racing the final
                // persist cannot write a duplicate entry for the same capture.
                val persistenceGuard = DictationCapturePersistenceGuard(capturePersistence)
                var resultText = ""
                inlineTranscription.transcribe(
                    request =
                        InlineTranscriptionRequest(
                            samples = samples,
                            llmEnabled = llmEnabled,
                            processingModeId = processingMode.id,
                            correlationPrefix = "voice",
                        ),
                    persistence = persistenceGuard,
                    commitText = { text ->
                        resultText = text
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
                        Log.d(TAG, "Returning result to caller: '${delivery.text}'")
                        dismissWithResult(
                            resultCode = Activity.RESULT_OK,
                            data =
                                Intent().apply {
                                    putStringArrayListExtra(
                                        RecognizerIntent.EXTRA_RESULTS,
                                        arrayListOf(delivery.text),
                                    )
                                },
                        )
                    }

                    is RecognitionDelivery.Failure -> returnError(delivery.errorCode)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Error during recognition", e)
                captureGate.releaseError("Failed to stop voice recognition", e)
                returnError(android.speech.SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }

    private fun cancelRecording() {
        if (_recordingState.value is RecordingState.Stopping) {
            // The inline pipeline is mid-transcription; finishing this activity cancels
            // it via lifecycleScope. Mark the cancellation as user-initiated so the
            // pipeline discards per the save preference instead of force-rescuing. An
            // unmarked cancellation (system kill, task swipe) still rescues the capture.
            inlineTranscription.markUserCancelled()
        }
        recordingJob?.cancel()
        recorder.stop()
        captureGate.releaseCompleted()
        _recordingState.value = RecordingState.Idle
        dismissWithResult(Activity.RESULT_CANCELED)
    }

    override fun onDestroy() {
        if (captureGate.isHeld()) {
            val samples = recorder.stop()
            captureGate.releaseCompleted()
            // Once stopRecording hands samples to the inline pipeline the state is
            // Stopping and the pipeline owns persistence (it rescues on cancellation
            // itself); rescuing here too would duplicate the same capture.
            if (_recordingState.value !is RecordingState.Stopping) {
                rescueInterruptedCapture(samples)
            }
        }
        recorder.close()
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
                    // Not user-initiated: a user cancel releases the gate before destroy,
                    // so a held gate here means the system interrupted the capture.
                    reason = InlineCapturePersistReason.RESCUE,
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Failed to rescue interrupted recognition audio", e)
            }
        }
    }

    private fun returnError(errorCode: Int) {
        Log.w(TAG, "Returning canceled result with error code: $errorCode")
        val results =
            Intent().apply {
                putExtra(RecognizerIntent.EXTRA_RESULTS, ArrayList<String>())
            }
        dismissWithResult(Activity.RESULT_CANCELED, results)
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
    }
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
