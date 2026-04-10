package dev.chirpboard.app.core.recording

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton that coordinates recording state across all sources (App, Keyboard, Widget).
 * 
 * Only ONE recording can be active at a time. This manager uses atomic operations
 * to prevent race conditions when multiple sources attempt to start recording.
 * 
 * Usage:
 * ```
 * val result = recordingStateManager.tryStartRecording(RecordingOrigin.APP, profileId)
 * when (result) {
 *     is RecordingStartResult.Success -> // proceed with recording
 *     is RecordingStartResult.AlreadyRecording -> // show error to user
 * }
 * ```
 */
@Singleton
class RecordingStateManager @Inject constructor() {
    
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    
    /** Current recording state. Observe this to react to state changes. */
    val state: StateFlow<RecordingState> = _state.asStateFlow()
    
    /** Real-time audio amplitude (0-1) for waveform visualization. */
    private val _amplitude = MutableStateFlow(0f)
    val amplitudeFlow: StateFlow<Float> = _amplitude.asStateFlow()
    
    /** Buffer of recent amplitude samples for waveform display. */
    val waveformBuffer = WaveformBuffer(AMPLITUDE_HISTORY_SIZE)
    
    /** Monotonic sample counter for smooth waveform scrolling. */
    private val _amplitudeSampleCount = MutableStateFlow(0L)
    val amplitudeSampleCountFlow: StateFlow<Long> = _amplitudeSampleCount.asStateFlow()
    
    /** ID of the last recording that was completed successfully. 
     *  UI observes this to navigate to the recording detail after saving. */
    private val _lastCompletedRecordingId = MutableStateFlow<UUID?>(null)
    val lastCompletedRecordingId: StateFlow<UUID?> = _lastCompletedRecordingId.asStateFlow()
    
    /**
     * Accumulated time from previous segments (paused recordings).
     * Thread-safe via AtomicLong to prevent race conditions when
     * multiple threads read/write during pause/resume operations.
     */
    private val accumulatedSegmentMs = AtomicLong(0L)
    
    /** Atomic lock to prevent concurrent start attempts */
    private val recordingLock = AtomicBoolean(false)
    
    /** Scope for internal operations like timeouts */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timeoutJob: Job? = null
    companion object {
        private const val TAG = "RecordingStateManager"
        private const val AMPLITUDE_HISTORY_SIZE = 150 // Holds enough history for visible bars without wasting memory
        private const val STOPPING_TIMEOUT_MS = 5000L
    }
    
    /**
     * Attempt to start a recording from the given origin.
     * 
     * This is atomic - if another recording is already in progress or starting,
     * this will fail with [RecordingStartResult.AlreadyRecording].
     * 
     * @param origin Where the recording is being started from
     * @param profileId Optional profile to use for recording settings
     * @return Success if recording can start, AlreadyRecording otherwise
     */
    fun tryStartRecording(
        origin: RecordingOrigin,
        profileId: UUID? = null,
        onTransition: (RecordingState) -> Unit = {}
    ): RecordingStartResult {
        // Atomic check-and-set: only one caller can acquire the lock
        if (!recordingLock.compareAndSet(false, true)) {
            val currentState = _state.value
            return RecordingStartResult.AlreadyRecording(
                currentOrigin = currentState.activeOrigin ?: RecordingOrigin.APP
            )
        }
        
        // We have the lock - update state to Starting
        accumulatedSegmentMs.set(0L)
        _state.update { current ->
            Log.d(TAG, "State: ${current::class.simpleName} -> Starting")
            RecordingState.Starting(origin).also(onTransition)
        }
        return RecordingStartResult.Success
    }
    
    /**
     * Transition from Starting to Recording state.
     * Call this once audio capture has actually begun.
     * 
     * @param audioFilePath Path where audio is being recorded
     */
    fun onRecordingStarted(audioFilePath: String, onTransition: (RecordingState) -> Unit = {}) {
        _state.update { current ->
            when (current) {
                is RecordingState.Starting -> {
                    Log.d(TAG, "State: Starting -> Recording")
                    RecordingState.Recording(
                        origin = current.origin,
                        profileId = current.profileId,
                        audioFilePath = audioFilePath
                    ).also(onTransition)




                }
                else -> {
                    Log.w(TAG, "onRecordingStarted called in wrong state: ${current::class.simpleName}")
                    current // Don't change state
                }
            }
        }
    }
    
    /**
     * Pause the current recording.
     * Only valid when in Recording state.
     */
    fun pauseRecording() {
        while (true) {
            val current = _state.value
            if (current !is RecordingState.Recording) {
                Log.w(TAG, "pauseRecording called in wrong state: ${current::class.simpleName}")
                break
            }
            val elapsedThisSegment = System.currentTimeMillis() - current.startTimeMs
            val totalAccumulated = accumulatedSegmentMs.get() + elapsedThisSegment
            val nextState = RecordingState.Paused(
                origin = current.origin,
                profileId = current.profileId,
                audioFilePath = current.audioFilePath,
                accumulatedMs = totalAccumulated
            )
            if (_state.compareAndSet(current, nextState)) {
                accumulatedSegmentMs.set(totalAccumulated)
                Log.d(TAG, "State: Recording -> Paused")
                break
            }
        }
    }
    
    /**
     * Resume a paused recording.
     * Only valid when in Paused state.
     */
    fun resumeRecording() {
        _state.update { current ->
            when (current) {
                is RecordingState.Paused -> {
                    Log.d(TAG, "State: Paused -> Recording")
                    RecordingState.Recording(
                        origin = current.origin,
                        profileId = current.profileId,
                        // Set startTimeMs so that (now - startTimeMs + accumulatedMs) == total recorded time
                        startTimeMs = System.currentTimeMillis(),
                        audioFilePath = current.audioFilePath
                    )
                }
                else -> {
                    Log.w(TAG, "resumeRecording called in wrong state: ${current::class.simpleName}")
                    current // Don't change state
                }
            }
        }
    }
    
    /**
     * Begin stopping the current recording.
     * Returns a Job that will force-cancel after STOPPING_TIMEOUT_MS if stop doesn't complete.
     * Caller should cancel this job when stop completes successfully.
     */
    fun beginStopRecording(): Job? {
        var transitioned = false
        
        _state.update { current ->
            when (current) {
                is RecordingState.Starting,
                is RecordingState.Recording,
                is RecordingState.Paused -> {
                    transitioned = true
                    Log.d(TAG, "State: ${current::class.simpleName} -> Stopping")
                    RecordingState.Stopping(
                        origin = when (current) {
                            is RecordingState.Starting -> current.origin
                            is RecordingState.Recording -> current.origin
                            is RecordingState.Paused -> current.origin
                            else -> RecordingOrigin.APP
                        },
                    )
                }
                else -> {
                    Log.w(TAG, "beginStopRecording called in wrong state: ${current::class.simpleName}")
                    current
                }
            }
        }
        
        if (!transitioned) return null
        
        // Launch timeout recovery
        timeoutJob = scope.launch {
            delay(STOPPING_TIMEOUT_MS)
            var timedOut = false
            _state.update { current ->
                if (current is RecordingState.Stopping) {
                    timedOut = true
                    Log.w(TAG, "Stopping state timed out after ${STOPPING_TIMEOUT_MS}ms, forcing to Idle")
                    RecordingState.Error(current.activeOrigin ?: RecordingOrigin.APP, "Failed to stop recording")
                } else {
                    current
                }
            }
            if (timedOut) {
                recordingLock.set(false)
            }
        }
        return timeoutJob
    }
    
    /**
     * Recording has completed successfully.
     * This releases the lock and returns to Idle state.
     *
     * @param recordingId The ID of the saved recording, if available
     */
    fun onRecordingCompleted(recordingId: UUID? = null) {
        timeoutJob?.cancel()
        _lastCompletedRecordingId.value = recordingId
        _state.update { current ->
            when (current) {
                is RecordingState.Stopping -> {
                    Log.d(TAG, "State: Stopping -> Idle")
                    RecordingState.Idle
                }
                else -> {
                    // Fallback: still transition to Idle and release lock
                    Log.w(TAG, "onRecordingCompleted called in unexpected state: ${current::class.simpleName}, forcing to Idle")
                    RecordingState.Idle
                }
            }
        }
        recordingLock.set(false)
        clearAmplitude()
    }
    
    /**
     * Clear the last completed recording ID.
     * Call after navigating to the recording detail screen to avoid re-triggering.
     */
    fun clearLastCompletedRecordingId() {
        _lastCompletedRecordingId.value = null
    }
    
    /**
     * Recording failed with an error.
     * This releases the lock after brief error state.
     * 
     * @param message User-facing error message
     * @param cause Optional underlying exception
     */
    fun onRecordingError(message: String, cause: Throwable? = null) {
        timeoutJob?.cancel()
        _state.update { current ->
            val origin = current.activeOrigin ?: RecordingOrigin.APP
            Log.d(TAG, "State: ${current::class.simpleName} -> Error")
            RecordingState.Error(origin, message, cause)
        }
        recordingLock.set(false)
    }
    
    /**
     * Clear error state and return to Idle.
     * Call this after the error has been handled/shown to the user.
     */
    fun clearError() {
        _state.update { current ->
            when (current) {
                is RecordingState.Error -> {
                    Log.d(TAG, "State: Error -> Idle (cleared)")
                    RecordingState.Idle
                }
                else -> {
                    Log.w(TAG, "clearError called in wrong state: ${current::class.simpleName}")
                    current // Don't change state
                }
            }
        }
    }
    
    /**
     * Force-cancel any recording in progress.
     * Use this for emergency cleanup (e.g., app being killed).
     */
    fun forceCancel() {
        timeoutJob?.cancel()
        accumulatedSegmentMs.set(0L)
        _state.update { current ->
            Log.d(TAG, "State: ${current::class.simpleName} -> Idle (force cancelled)")
            RecordingState.Idle
        }
        recordingLock.set(false)
        clearAmplitude()
    }
    
    /**
     * Update the current audio amplitude.
     * Call this from RecordingService during active recording.
     * 
     * @param amplitude Normalized amplitude value (0-1)
     */
    fun updateAmplitude(amplitude: Float) {
        val normalized = amplitude.coerceIn(0f, 1f)
        _amplitude.value = normalized
        _amplitudeSampleCount.update { it + 1 }
        
        waveformBuffer.add(normalized)
    }
    
    /**
     * Clear amplitude data.
     * Called when recording stops or is cancelled.
     */
    fun clearAmplitude() {
        _amplitude.value = 0f
        waveformBuffer.clear()
        _amplitudeSampleCount.value = 0L
    }
    
    /**
     * Check if a specific origin can start recording right now.
     * This is a non-blocking check for UI display purposes.
     */
    fun canStartRecording(): Boolean = !_state.value.isActive
    
    /**
     * Get the current recording duration in milliseconds, or 0 if not recording.
     * Accounts for paused time — only counts active recording segments.
     */
    fun getCurrentDurationMs(): Long {
        return when (val currentState = _state.value) {
            is RecordingState.Recording -> {
                accumulatedSegmentMs.get() + (System.currentTimeMillis() - currentState.startTimeMs)
            }
            is RecordingState.Paused -> {
                currentState.accumulatedMs
            }
            else -> 0L
        }
    }
}

/**
 * Result of attempting to start a recording.
 */
sealed class RecordingStartResult {
    /** Recording can proceed */
    object Success : RecordingStartResult()
    
    /** Another recording is already in progress */
    data class AlreadyRecording(
        val currentOrigin: RecordingOrigin
    ) : RecordingStartResult()
}
