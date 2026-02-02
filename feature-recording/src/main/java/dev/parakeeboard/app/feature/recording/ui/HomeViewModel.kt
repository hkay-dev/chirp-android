package dev.parakeeboard.app.feature.recording.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.parakeeboard.app.core.recording.RecordingOrigin
import dev.parakeeboard.app.core.recording.RecordingState
import dev.parakeeboard.app.core.recording.RecordingStartResult
import dev.parakeeboard.app.data.entity.Recording
import dev.parakeeboard.app.data.repository.RecordingRepository
import dev.parakeeboard.app.feature.recording.RecordingManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val recordingRepository: RecordingRepository,
    private val recordingManager: RecordingManager
) : ViewModel() {
    
    /** All recordings sorted by creation date (newest first) */
    val recordings: StateFlow<List<Recording>> = recordingRepository
        .getAllRecordings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    /** Current recording state */
    val recordingState: StateFlow<RecordingState> = recordingManager.state
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    /**
     * Toggle recording on/off.
     */
    fun toggleRecording(profileId: UUID? = null) {
        val result = recordingManager.toggleRecording(RecordingOrigin.APP, profileId)
        
        if (result is RecordingStartResult.AlreadyRecording) {
            val originText = when (result.currentOrigin) {
                RecordingOrigin.APP -> "the app"
                RecordingOrigin.KEYBOARD -> "the keyboard"
                RecordingOrigin.WIDGET -> "the widget"
            }
            _errorMessage.value = "Recording already in progress from $originText"
        }
    }
    
    /**
     * Delete a recording.
     */
    fun deleteRecording(recording: Recording) {
        viewModelScope.launch {
            // Delete the audio file
            recording.audioPath.let { path ->
                java.io.File(path).delete()
            }
            recordingRepository.delete(recording)
        }
    }
    
    /**
     * Clear error message.
     */
    fun clearError() {
        _errorMessage.value = null
        recordingManager.clearError()
    }
}
