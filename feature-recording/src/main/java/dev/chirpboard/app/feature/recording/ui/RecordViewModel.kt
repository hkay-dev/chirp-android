package dev.chirpboard.app.feature.recording.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.feature.recording.service.RecordingService
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for the full-screen RecordScreen.
 * 
 * Manages recording state and amplitude data for the recording interface.
 */
@HiltViewModel
class RecordViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingStateManager: RecordingStateManager
) : ViewModel() {
    
    /** Current recording state */
    val recordingState: StateFlow<RecordingState> = recordingStateManager.state
    
    /** Buffer of amplitude samples for waveform display */
    val amplitudeHistory: StateFlow<List<Float>> = recordingStateManager.amplitudeHistoryFlow
    
    /** Current audio amplitude (0-1) */
    val currentAmplitude: StateFlow<Float> = recordingStateManager.amplitudeFlow
    
    /**
     * Start a new recording.
     * 
     * @param profileId Optional profile ID for recording settings
     */
    fun startRecording(profileId: UUID? = null) {
        RecordingService.startRecording(
            context = context,
            origin = RecordingOrigin.APP,
            profileId = profileId
        )
    }
    
    /**
     * Stop the current recording and save it.
     */
    fun stopRecording() {
        RecordingService.stopRecording(context)
    }
    
    /**
     * Cancel the current recording without saving.
     */
    fun cancelRecording() {
        // Force cancel clears state without creating a database entry
        recordingStateManager.forceCancel()
        RecordingService.stopRecording(context)
    }
}
