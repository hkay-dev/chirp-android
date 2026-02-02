package dev.parakeeboard.app.feature.recording

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.parakeeboard.app.core.recording.RecordingOrigin
import dev.parakeeboard.app.core.recording.RecordingState
import dev.parakeeboard.app.core.recording.RecordingStartResult
import dev.parakeeboard.app.core.recording.RecordingStateManager
import dev.parakeeboard.app.feature.recording.service.RecordingService
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level API for managing recordings.
 * 
 * Use this from ViewModels instead of interacting with RecordingService directly.
 */
@Singleton
class RecordingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stateManager: RecordingStateManager
) {
    
    /** Current recording state */
    val state: StateFlow<RecordingState> = stateManager.state
    
    /** Whether a recording can be started right now */
    val canStartRecording: Boolean
        get() = stateManager.canStartRecording()
    
    /** Current recording duration in milliseconds */
    val currentDurationMs: Long
        get() = stateManager.getCurrentDurationMs()
    
    /**
     * Start a new recording.
     * 
     * @param origin Where the recording is being started from
     * @param profileId Optional profile to use for recording settings
     * @return Result indicating success or why recording couldn't start
     */
    fun startRecording(
        origin: RecordingOrigin = RecordingOrigin.APP,
        profileId: UUID? = null
    ): RecordingStartResult {
        // Pre-check before starting service (for immediate UI feedback)
        if (!canStartRecording) {
            val currentOrigin = state.value.activeOrigin ?: RecordingOrigin.APP
            return RecordingStartResult.AlreadyRecording(currentOrigin)
        }
        
        // Start the service (it will do the actual atomic check)
        RecordingService.startRecording(context, origin, profileId)
        return RecordingStartResult.Success
    }
    
    /**
     * Stop the current recording.
     */
    fun stopRecording() {
        RecordingService.stopRecording(context)
    }
    
    /**
     * Toggle recording on/off.
     * 
     * @param origin Where the recording is being toggled from
     * @param profileId Optional profile to use if starting
     * @return Result if starting, null if stopping
     */
    fun toggleRecording(
        origin: RecordingOrigin = RecordingOrigin.APP,
        profileId: UUID? = null
    ): RecordingStartResult? {
        return if (state.value.isActive) {
            stopRecording()
            null
        } else {
            startRecording(origin, profileId)
        }
    }
    
    /**
     * Clear any error state.
     */
    fun clearError() {
        stateManager.clearError()
    }
}
