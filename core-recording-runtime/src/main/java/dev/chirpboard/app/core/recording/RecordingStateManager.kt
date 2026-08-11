package dev.chirpboard.app.core.recording

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.atomic.AtomicReference
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
    
    /** Atomic lock to prevent concurrent start attempts */
    private val recordingLock = AtomicBoolean(false)
    
    /** Scope for internal operations like timeouts */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val timeoutJob = AtomicReference<Job?>(null)

    @Volatile
    private var lastAmplitudeEmitMs = 0L

    /**
     * The single registered stopping-timeout rescue handler, tagged with the origin it was
     * registered for. Only one recording can be active at a time (the global recording lock),
     * so at most one handler is ever live; the origin tag preserves the invariant that a
     * handler only fires when the stopping state's origin matches the one it registered for.
     */
    @Volatile
    private var stoppingTimeoutHandler: StoppingTimeoutHandlerRegistration? = null

    private class StoppingTimeoutHandlerRegistration(
        val origin: RecordingOrigin,
        val handler: suspend (RecordingState.Stopping) -> Unit,
    )

    /** Test-only override for stopping timeout duration. */
    @VisibleForTesting
    internal var stoppingTimeoutMsOverrideForTest: Long? = null

    /**
     * Test-only scope override so the stopping-timeout coroutine runs under a test
     * scheduler (virtual time) instead of the real Default dispatcher (TST-012).
     */
    @VisibleForTesting
    internal var timeoutScopeOverrideForTest: CoroutineScope? = null

    /**
     * Test-only clock override so duration accumulation and the amplitude throttle are
     * deterministic in unit tests (TST-012). Production always reads the real clock.
     */
    @VisibleForTesting
    internal var nowMsOverrideForTest: (() -> Long)? = null

    private fun nowMs(): Long = nowMsOverrideForTest?.invoke() ?: System.currentTimeMillis()

    companion object {
        private const val TAG = "RecordingStateManager"
        private const val AMPLITUDE_HISTORY_SIZE = 150
        private const val AMPLITUDE_THROTTLE_MS = 100L
        private const val STOPPING_TIMEOUT_BASE_MS = 15_000L
        private const val STOPPING_TIMEOUT_PER_MB_MS = 1_000L
        private const val STOPPING_TIMEOUT_MAX_MS = 120_000L

        fun computeStoppingTimeoutMs(fileSizeBytes: Long): Long {
            val sizeMb = fileSizeBytes / (1024 * 1024)
            return (STOPPING_TIMEOUT_BASE_MS + sizeMb * STOPPING_TIMEOUT_PER_MB_MS)
                .coerceAtMost(STOPPING_TIMEOUT_MAX_MS)
        }
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
    ): RecordingStartResult {
        // Atomic check-and-set: only one caller can acquire the lock
        if (!recordingLock.compareAndSet(false, true)) {
            val currentState = _state.value
            return RecordingStartResult.AlreadyRecording(
                currentOrigin = currentState.activeOrigin ?: RecordingOrigin.APP
            )
        }

        // We have the lock - update state to Starting
        _state.update { current ->
            Log.d(TAG, "State: ${current::class.simpleName} -> Starting")
            RecordingState.Starting(origin, profileId)
        }
        return RecordingStartResult.Success
    }

    /**
     * Assign the in-progress database recording ID while still in [RecordingState.Starting].
     * Call after [createInProgressRecording] and before capture begins.
     */
    fun onRecordingIdAssigned(recordingId: UUID) {
        _state.update { current ->
            when (current) {
                is RecordingState.Starting -> current.copy(recordingId = recordingId)
                else -> {
                    Log.w(TAG, "onRecordingIdAssigned called in wrong state: ${current::class.simpleName}")
                    current
                }
            }
        }
    }
    
    /**
     * Transition from Starting to Recording state.
     * Call this once audio capture has actually begun.
     * 
     * @param audioFilePath Path where audio is being recorded
     */
    fun onRecordingStarted(
        audioFilePath: String,
        recordingId: UUID? = null,
    ) {
        _state.update { current ->
            when (current) {
                is RecordingState.Starting -> {
                    Log.d(TAG, "State: Starting -> Recording")
                    RecordingState.Recording(
                        origin = current.origin,
                        profileId = current.profileId,
                        startTimeMs = nowMs(),
                        audioFilePath = audioFilePath,
                        recordingId = recordingId ?: current.recordingId,
                        // A fresh session always starts its first segment at zero; pause/resume
                        // and rotation carry the accumulated total forward through the state.
                        accumulatedBeforeSegmentMs = 0L,
                    )
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
            val elapsedThisSegment = nowMs() - current.startTimeMs
            val totalAccumulated = current.accumulatedBeforeSegmentMs + elapsedThisSegment
            val nextState = RecordingState.Paused(
                origin = current.origin,
                profileId = current.profileId,
                audioFilePath = current.audioFilePath,
                accumulatedMs = totalAccumulated,
                recordingId = current.recordingId,
            )
            if (_state.compareAndSet(current, nextState)) {
                Log.d(TAG, "State: Recording -> Paused")
                break
            }
        }
    }
    
    /**
     * Resume a paused recording.
     * When [newAudioFilePath] is provided, capture continues on a fresh hidden segment file.
     */
    fun resumeRecording(newAudioFilePath: String? = null) {
        _state.update { current ->
            when (current) {
                is RecordingState.Paused -> {
                    Log.d(TAG, "State: Paused -> Recording")
                    RecordingState.Recording(
                        origin = current.origin,
                        profileId = current.profileId,
                        startTimeMs = nowMs(),
                        audioFilePath = newAudioFilePath ?: current.audioFilePath,
                        recordingId = current.recordingId,
                        accumulatedBeforeSegmentMs = current.accumulatedMs,
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
     * Transition to Stopping without starting the post-release timeout yet.
     */
    fun transitionToStopping(): Boolean {
        var transitioned = false

        _state.update { current ->
            // update() retries this lambda on a lost CAS; reset the flag so a retry that
            // takes the ignore branch cannot report a transition that never happened.
            transitioned = false
            when (current) {
                is RecordingState.Starting,
                is RecordingState.Recording,
                is RecordingState.Paused,
                -> {
                    transitioned = true
                    Log.d(TAG, "State: ${current::class.simpleName} -> Stopping")
                    RecordingState.Stopping(
                        origin = current.activeOrigin ?: RecordingOrigin.APP,
                        profileId = when (current) {
                            is RecordingState.Starting -> current.profileId
                            is RecordingState.Recording -> current.profileId
                            is RecordingState.Paused -> current.profileId
                            else -> null
                        },
                        audioFilePath = when (current) {
                            is RecordingState.Recording -> current.audioFilePath
                            is RecordingState.Paused -> current.audioFilePath
                            else -> null
                        },
                        recordingId = when (current) {
                            is RecordingState.Starting -> current.recordingId
                            is RecordingState.Recording -> current.recordingId
                            is RecordingState.Paused -> current.recordingId
                            else -> null
                        },
                    )
                }
                else -> {
                    Log.w(TAG, "transitionToStopping called in wrong state: ${current::class.simpleName}")
                    current
                }
            }
        }

        return transitioned
    }

    /**
     * Register a handler invoked when stop exceeds its timeout budget for the given origin.
     */
    fun setStoppingTimeoutHandler(
        origin: RecordingOrigin,
        handler: (suspend (RecordingState.Stopping) -> Unit)?,
    ) {
        if (handler == null) {
            // A null handler clears only this origin's registration, mirroring the
            // previous per-origin removal.
            if (stoppingTimeoutHandler?.origin == origin) {
                stoppingTimeoutHandler = null
            }
        } else {
            stoppingTimeoutHandler = StoppingTimeoutHandlerRegistration(origin, handler)
        }
    }

    /**
     * Remove the stopping-timeout handler for [origin] only when [handler] is the one
     * currently registered. Lets an owner tear down its own handler without clobbering
     * a replacement registered by a newer owner instance.
     */
    fun clearStoppingTimeoutHandler(
        origin: RecordingOrigin,
        handler: suspend (RecordingState.Stopping) -> Unit,
    ) {
        val current = stoppingTimeoutHandler
        if (current != null && current.origin == origin && current.handler === handler) {
            stoppingTimeoutHandler = null
        }
    }

    /**
     * Begin stopping timeout after recorder release completes.
     */
    fun startStoppingTimeout(fileSizeBytes: Long) {
        val timeoutMs = stoppingTimeoutMsOverrideForTest ?: computeStoppingTimeoutMs(fileSizeBytes)
        val started =
            (timeoutScopeOverrideForTest ?: scope).launch {
                delay(timeoutMs)
                val stoppingState = _state.value
                if (stoppingState !is RecordingState.Stopping) {
                    return@launch
                }
                // Handler cleanup (journal abandon, row delete) must finish before Error + lock release.
                val registration = stoppingTimeoutHandler
                if (registration != null && registration.origin == stoppingState.origin) {
                    try {
                        registration.handler.invoke(stoppingState)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A throwing rescue handler must not wedge the state machine: without
                        // this catch the exception escapes the scope (no CoroutineExceptionHandler),
                        // the state stays Stopping forever, the recording lock is never released,
                        // and the uncaught exception kills the process hosting the IME.
                        Log.e(TAG, "Stopping timeout handler failed; forcing Error state", e)
                    }
                }
                var timedOut = false
                _state.update { current ->
                    // Identity, not `is Stopping`: the rescue handler above does journal and
                    // DB work that can take seconds, during which the original session may
                    // finish, release the lock, and a new session reach Stopping of its own.
                    // Forcing that newer session to Error would abort a healthy stop.
                    if (current === stoppingState) {
                        timedOut = true
                        Log.w(TAG, "Stopping state timed out after ${timeoutMs}ms, forcing to Error")
                        RecordingState.Error(
                            stoppingState.origin,
                            "Failed to stop recording",
                        )
                    } else {
                        current
                    }
                }
                if (timedOut) {
                    recordingLock.set(false)
                }
            }
        // getAndSet, not cancel-then-assign: two concurrent starts (a service stop racing the
        // keyboard coordinator) would otherwise both cancel the same old job and both launch,
        // leaving one orphan that no later cancel can reach — and that orphan still forces
        // Error and releases the lock when it fires.
        timeoutJob.getAndSet(started)?.cancel()
    }

    /**
     * Capture has stopped and finalize work was enqueued.
     * Releases the recording lock immediately while the DB row remains in-progress until finalize completes.
     */
    fun onCaptureStopHandoff(recordingId: UUID?) {
        var handoffAccepted = false
        _state.update { current ->
            // update() retries this lambda on a lost CAS; reset the flag so a retry that
            // takes an ignore branch cannot run the acceptance side effects below.
            handoffAccepted = false
            when (current) {
                is RecordingState.Starting,
                is RecordingState.Recording,
                is RecordingState.Paused,
                is RecordingState.Stopping,
                -> {
                    val currentRecordingId = current.activeRecordingId
                    if (recordingId != null && currentRecordingId != recordingId) {
                        Log.w(
                            TAG,
                            "Ignoring stale capture handoff for $recordingId while active recording is $currentRecordingId",
                        )
                        return@update current
                    }
                    if (recordingId == null && currentRecordingId != null) {
                        // Defense-in-depth: a null handoff is legitimate only for sessions
                        // that never created a recording row. When the active state carries
                        // a recordingId, an id-less handoff is inconsistent and must not
                        // force Idle (and release the global lock) under a live session;
                        // the stopping timeout recovers the state machine if needed.
                        Log.w(
                            TAG,
                            "Ignoring null capture handoff while active recording is $currentRecordingId",
                        )
                        return@update current
                    }
                    handoffAccepted = true
                    Log.d(TAG, "State: ${current::class.simpleName} -> Idle (capture handoff)")
                    RecordingState.Idle
                }
                else -> {
                    Log.w(TAG, "Ignoring capture handoff in unexpected state: ${current::class.simpleName}")
                    current
                }
            }
        }
        if (handoffAccepted) {
            timeoutJob.getAndSet(null)?.cancel()
            publishCompletedRecordingId(recordingId)
            recordingLock.set(false)
            clearAmplitude()
        }
    }

    /**
     * Recording has completed successfully.
     * This releases the lock and returns to Idle state.
     *
     * @param recordingId The ID of the saved recording, if available
     */
    fun onRecordingCompleted(recordingId: UUID? = null) {
        var completionAccepted = false
        _state.update { current ->
            // update() retries this lambda on a lost CAS; reset the flag so a retry that
            // takes an ignore branch cannot run the acceptance side effects below.
            completionAccepted = false
            val currentRecordingId = current.activeRecordingId
            if (recordingId != null && currentRecordingId != null && currentRecordingId != recordingId) {
                Log.w(
                    TAG,
                    "Ignoring stale recording completion for $recordingId while active recording is $currentRecordingId",
                )
                return@update current
            }
            if (recordingId == null && currentRecordingId != null) {
                // Same defense as onCaptureStopHandoff: only service-driven sessions carry a
                // recordingId in state, and they never complete id-less. An id-less completion
                // arriving here is a late callback from an earlier session; accepting it would
                // force the live session to Idle and release the lock under a running capture.
                Log.w(
                    TAG,
                    "Ignoring null recording completion while active recording is $currentRecordingId",
                )
                return@update current
            }
            completionAccepted = true
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
        if (completionAccepted) {
            timeoutJob.getAndSet(null)?.cancel()
            publishCompletedRecordingId(recordingId)
            recordingLock.set(false)
            clearAmplitude()
        }
    }
    
    /**
     * Keyboard, quick-capture and recognition sessions complete without a recording id, so an
     * id-less completion has nothing to navigate to. Overwriting with null would swallow the
     * pending target an app recording just published, and the Record screen would never open it.
     */
    private fun publishCompletedRecordingId(recordingId: UUID?) {
        if (recordingId != null) {
            _lastCompletedRecordingId.value = recordingId
        }
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
     * @param recordingId The failed session's recording ID when the caller has one; lets a
     *   late async-teardown failure be ignored instead of overwriting a newer live session.
     */
    fun onRecordingError(
        message: String,
        cause: Throwable? = null,
        recordingId: UUID? = null,
    ) {
        var errorAccepted = false
        _state.update { current ->
            errorAccepted = false
            val currentRecordingId = current.activeRecordingId
            if (recordingId != null && currentRecordingId != null && currentRecordingId != recordingId) {
                Log.w(
                    TAG,
                    "Ignoring stale recording error for $recordingId while active recording is $currentRecordingId",
                )
                return@update current
            }
            errorAccepted = true
            val origin = current.activeOrigin ?: RecordingOrigin.APP
            Log.d(TAG, "State: ${current::class.simpleName} -> Error")
            RecordingState.Error(origin, message, cause)
        }
        if (errorAccepted) {
            timeoutJob.getAndSet(null)?.cancel()
            recordingLock.set(false)
            clearAmplitude()
        }
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
        timeoutJob.getAndSet(null)?.cancel()
        _state.update { current ->
            Log.d(TAG, "State: ${current::class.simpleName} -> Idle (force cancelled)")
            RecordingState.Idle
        }
        recordingLock.set(false)
        clearAmplitude()
    }
    
    /**
     * Rotate to a new capture segment without changing recording state.
     * Accumulates elapsed time from the current segment and resets the segment clock.
     */
    fun rotateSegment(newAudioFilePath: String) {
        while (true) {
            val current = _state.value
            if (current !is RecordingState.Recording) {
                Log.w(TAG, "rotateSegment called in wrong state: ${current::class.simpleName}")
                break
            }
            // One clock reading for both halves of the rotation: taking a second one for the
            // new segment's start would silently drop everything in between (a GC pause, or a
            // CAS retry looping back through here) from the recording's accumulated duration.
            val rotatedAtMs = nowMs()
            val elapsedThisSegment = rotatedAtMs - current.startTimeMs
            val totalAccumulated = current.accumulatedBeforeSegmentMs + elapsedThisSegment
            val nextState =
                RecordingState.Recording(
                    origin = current.origin,
                    profileId = current.profileId,
                    startTimeMs = rotatedAtMs,
                    audioFilePath = newAudioFilePath,
                    recordingId = current.recordingId,
                    accumulatedBeforeSegmentMs = totalAccumulated,
                )
            if (_state.compareAndSet(current, nextState)) {
                Log.d(TAG, "Rotated capture segment; accumulatedMs=$totalAccumulated")
                break
            }
        }
    }

    /**
     * Update the current audio amplitude.
     * Call this from RecordingService during active recording.
     * 
     * @param amplitude Normalized amplitude value (0-1)
     */
    fun updateAmplitude(amplitude: Float) {
        val now = nowMs()
        // An explicit zero is a reset (pause, capture error) and must always land; the
        // throttle used to swallow it when it arrived within 100ms of the last sample,
        // leaving the level meter pinned at the last value for the whole pause.
        if (amplitude != 0f && now - lastAmplitudeEmitMs < AMPLITUDE_THROTTLE_MS) {
            return
        }
        lastAmplitudeEmitMs = now

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
        lastAmplitudeEmitMs = 0L
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
                currentState.accumulatedBeforeSegmentMs + (nowMs() - currentState.startTimeMs)
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
