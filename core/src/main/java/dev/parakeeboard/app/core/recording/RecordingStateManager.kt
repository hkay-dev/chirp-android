package dev.parakeeboard.app.core.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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
    
    /** Atomic lock to prevent concurrent start attempts */
    private val recordingLock = AtomicBoolean(false)
    
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
        profileId: UUID? = null
    ): RecordingStartResult {
        // Atomic check-and-set: only one caller can acquire the lock
        if (!recordingLock.compareAndSet(false, true)) {
            val currentState = _state.value
            return RecordingStartResult.AlreadyRecording(
                currentOrigin = currentState.activeOrigin ?: RecordingOrigin.APP
            )
        }
        
        // We have the lock - update state to Starting
        _state.value = RecordingState.Starting(origin, profileId)
        return RecordingStartResult.Success
    }
    
    /**
     * Transition from Starting to Recording state.
     * Call this once audio capture has actually begun.
     * 
     * @param audioFilePath Path where audio is being recorded
     */
    fun onRecordingStarted(audioFilePath: String) {
        val currentState = _state.value
        if (currentState is RecordingState.Starting) {
            _state.value = RecordingState.Recording(
                origin = currentState.origin,
                profileId = currentState.profileId,
                audioFilePath = audioFilePath
            )
        }
    }
    
    /**
     * Begin stopping the current recording.
     */
    fun beginStopRecording() {
        val currentState = _state.value
        when (currentState) {
            is RecordingState.Starting -> {
                _state.value = RecordingState.Stopping(
                    origin = currentState.origin,
                    profileId = currentState.profileId
                )
            }
            is RecordingState.Recording -> {
                _state.value = RecordingState.Stopping(
                    origin = currentState.origin,
                    profileId = currentState.profileId
                )
            }
            else -> {
                // Not in a state where stopping makes sense
            }
        }
    }
    
    /**
     * Recording has completed successfully.
     * This releases the lock and returns to Idle state.
     */
    fun onRecordingCompleted() {
        _state.value = RecordingState.Idle
        recordingLock.set(false)
    }
    
    /**
     * Recording failed with an error.
     * This releases the lock after brief error state.
     * 
     * @param message User-facing error message
     * @param cause Optional underlying exception
     */
    fun onRecordingError(message: String, cause: Throwable? = null) {
        val currentState = _state.value
        val origin = currentState.activeOrigin ?: RecordingOrigin.APP
        
        _state.value = RecordingState.Error(origin, message, cause)
        recordingLock.set(false)
    }
    
    /**
     * Clear error state and return to Idle.
     * Call this after the error has been handled/shown to the user.
     */
    fun clearError() {
        if (_state.value is RecordingState.Error) {
            _state.value = RecordingState.Idle
        }
    }
    
    /**
     * Force-cancel any recording in progress.
     * Use this for emergency cleanup (e.g., app being killed).
     */
    fun forceCancel() {
        _state.value = RecordingState.Idle
        recordingLock.set(false)
    }
    
    /**
     * Check if a specific origin can start recording right now.
     * This is a non-blocking check for UI display purposes.
     */
    fun canStartRecording(): Boolean = !_state.value.isActive
    
    /**
     * Get the current recording duration in milliseconds, or 0 if not recording.
     */
    fun getCurrentDurationMs(): Long {
        val currentState = _state.value
        return if (currentState is RecordingState.Recording) {
            System.currentTimeMillis() - currentState.startTimeMs
        } else {
            0L
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
