package dev.parakeeboard.app.feature.recording.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.parakeeboard.app.data.entity.Recording
import dev.parakeeboard.app.data.entity.Transcript
import dev.parakeeboard.app.data.repository.RecordingRepository
import dev.parakeeboard.app.feature.recording.audio.AudioPlayer
import dev.parakeeboard.app.feature.recording.audio.PlaybackState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class RecordingDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val recordingRepository: RecordingRepository,
    private val audioPlayer: AudioPlayer
) : ViewModel() {
    
    private val recordingId: UUID = savedStateHandle.get<String>("recordingId")
        ?.let { UUID.fromString(it) }
        ?: throw IllegalArgumentException("recordingId is required")
    
    val recording: StateFlow<Recording?> = recordingRepository
        .getRecordingFlow(recordingId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    
    val transcript: StateFlow<Transcript?> = recordingRepository
        .getTranscriptFlow(recordingId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    
    val playbackState: StateFlow<PlaybackState> = audioPlayer.state
    
    private val _isEditing = MutableStateFlow(false)
    val isEditing: StateFlow<Boolean> = _isEditing.asStateFlow()
    
    private val _editedTitle = MutableStateFlow("")
    val editedTitle: StateFlow<String> = _editedTitle.asStateFlow()
    
    fun startEditing() {
        _editedTitle.value = recording.value?.title ?: ""
        _isEditing.value = true
    }
    
    fun cancelEditing() {
        _isEditing.value = false
    }
    
    fun updateTitle(newTitle: String) {
        _editedTitle.value = newTitle
    }
    
    fun saveTitle() {
        viewModelScope.launch {
            val trimmedTitle = _editedTitle.value.trim()
            if (trimmedTitle.isNotEmpty()) {
                recordingRepository.updateTitle(recordingId, trimmedTitle)
            }
            _isEditing.value = false
        }
    }
    
    fun deleteRecording(onDeleted: () -> Unit) {
        viewModelScope.launch {
            recording.value?.let { rec ->
                audioPlayer.release()
                File(rec.audioPath).delete()
                recordingRepository.delete(rec)
                onDeleted()
            }
        }
    }
    
    // Audio playback controls
    
    fun loadAudio() {
        recording.value?.let { rec ->
            audioPlayer.load(rec.audioPath, autoPlay = false)
        }
    }
    
    fun togglePlayPause() {
        val currentState = playbackState.value
        if (currentState is PlaybackState.Idle) {
            recording.value?.let { rec ->
                audioPlayer.load(rec.audioPath, autoPlay = true)
            }
        } else {
            audioPlayer.togglePlayPause()
        }
    }
    
    fun seekTo(positionMs: Long) {
        audioPlayer.seekTo(positionMs)
    }
    
    fun skipBackward() {
        audioPlayer.skipBackward()
    }
    
    fun skipForward() {
        audioPlayer.skipForward()
    }
    
    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
    }
}
