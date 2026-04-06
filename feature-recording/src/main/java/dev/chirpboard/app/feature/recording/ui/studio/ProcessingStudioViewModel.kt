package dev.chirpboard.app.feature.recording.ui.studio

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

import kotlinx.collections.immutable.toImmutableList
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.llm.model.ChatMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@HiltViewModel
class ProcessingStudioViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val repository: RecordingRepository,
    private val llmClient: LlmClient
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProcessingStudioState())
    val uiState: StateFlow<ProcessingStudioState> = _uiState.asStateFlow()

    private var player: ExoPlayer? = null
    private var progressJob: Job? = null
    private var rawTranscript: String = ""

    init {
        val recordingIdStr = savedStateHandle.get<String>("recordingId")
        if (!recordingIdStr.isNullOrEmpty() && recordingIdStr != "-1") {
            try {
                loadRecording(UUID.fromString(recordingIdStr))
            } catch (e: Exception) {
                // Invalid UUID
            }
        }
    }

    private fun loadRecording(id: UUID) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val recording = repository.getRecording(id)
            val transcript = repository.getTranscript(id)
            
            if (recording != null) {
                rawTranscript = transcript?.rawText ?: ""
                val rawText = transcript?.rawText ?: ""
                val tokens = rawText.split("\\s+".toRegex()).filter { it.isNotBlank() }
                val duration = recording.durationMs
                val words = tokens.mapIndexed { index, token ->
                    val start = if (tokens.isNotEmpty()) (duration * index) / tokens.size else 0L
                    val end = if (tokens.isNotEmpty()) (duration * (index + 1)) / tokens.size else 0L
                    TranscriptWord(
                        word = token,
                        startTimestampMs = start,
                        endTimestampMs = end,
                        confidence = 1.0f
                    )
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    transcriptWords = words.toImmutableList(),
                    summary = transcript?.summary ?: ""
                )

                initPlayer(recording.audioPath)
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    private fun initPlayer(audioPath: String) {
        player = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(audioPath))
            prepare()
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                    if (isPlaying) {
                        startProgressUpdates()
                    } else {
                        progressJob?.cancel()
                    }
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        _uiState.value = _uiState.value.copy(durationMs = duration)
                    }
                }
            })
        }
    }

    fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) {
                it.pause()
            } else {
                it.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
        _uiState.value = _uiState.value.copy(currentPositionMs = positionMs)
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive && player?.isPlaying == true) {
                _uiState.value = _uiState.value.copy(
                    currentPositionMs = player?.currentPosition ?: 0L
                )
                delay(500) // Lower frequency for VM updates, the UI uses LaunchedEffect for smooth interpolation
            }
        }
    }

    fun onWordClicked(timestamp: Long) {
        seekTo(timestamp)
        player?.play()
    }

    fun onSendChatMessage(text: String) {
        if (text.isBlank()) return

        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = text,
            isFromUser = true,
            timestamp = System.currentTimeMillis()
        )

        _uiState.value = _uiState.value.copy(
            chatMessages = (_uiState.value.chatMessages + userMsg).toImmutableList(),
            isTyping = true
        )

        viewModelScope.launch {
            val result = llmClient.generateChatResponse(rawTranscript, _uiState.value.chatMessages)
            val aiText = result.getOrNull() ?: "Sorry, I encountered an error."
            
            val aiMsg = ChatMessage(
                id = UUID.randomUUID().toString(),
                text = aiText,
                isFromUser = false,
                timestamp = System.currentTimeMillis()
            )

            _uiState.value = _uiState.value.copy(
                chatMessages = (_uiState.value.chatMessages + aiMsg).toImmutableList(),
                isTyping = false
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        player?.release()
        player = null
    }
}
