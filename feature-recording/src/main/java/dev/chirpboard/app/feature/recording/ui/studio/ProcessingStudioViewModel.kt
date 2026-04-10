package dev.chirpboard.app.feature.recording.ui.studio

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import javax.inject.Inject

import kotlinx.collections.immutable.toImmutableList
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
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
    private var currentRecordingId: UUID? = null

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

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
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Invalid UUID
            }
        }
    }

    private fun loadRecording(id: UUID) {
        viewModelScope.launch {
        currentRecordingId = id

            _uiState.value = _uiState.value.copy(isLoading = true)
            combine(
                repository.getRecordingFlow(id),
                repository.getTranscriptFlow(id)
            ) { recording, transcript ->
                Pair(recording, transcript)
            }.collectLatest { (recording, transcript) ->
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
                        status = recording.status,
                        transcriptWords = words.toImmutableList(),
                        summary = transcript?.summary ?: "",
                        title = recording.title,
                        createdAt = recording.createdAt.time,
                        audioPath = recording.audioPath,
                        source = recording.source
                    )

                    if (player == null) {
                        initPlayer(recording.audioPath)
                    }
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
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

    fun startEditingTitle() {
        _uiState.value = _uiState.value.copy(
            isEditingTitle = true,
            editedTitle = _uiState.value.title
        )
    }

    fun updateEditedTitle(newTitle: String) {
        _uiState.value = _uiState.value.copy(editedTitle = newTitle)
    }

    fun cancelEditingTitle() {
        _uiState.value = _uiState.value.copy(isEditingTitle = false)
    }

    fun saveTitle() {
        viewModelScope.launch {
            val id = currentRecordingId ?: return@launch
            val trimmedTitle = _uiState.value.editedTitle.trim()
            if (trimmedTitle.isNotEmpty()) {
                repository.updateTitle(id, trimmedTitle)
                _uiState.value = _uiState.value.copy(title = trimmedTitle)
            }
            _uiState.value = _uiState.value.copy(isEditingTitle = false)
        }
    }

    fun deleteRecording(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val id = currentRecordingId ?: return@launch
            val rec = repository.getRecording(id) ?: return@launch
            player?.release()
            player = null
            try {
                repository.delete(rec)
                withContext(Dispatchers.IO) {
                    try {
                        val file = File(rec.audioPath)
                        if (file.exists() && !file.delete()) {
                            Log.w("ProcessingStudioVM", "Failed to delete audio file: ${rec.audioPath}")
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.w("ProcessingStudioVM", "Error deleting audio file: ${rec.audioPath}", e)
                    }
                }
                onDeleted()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("ProcessingStudioVM", "Failed to delete recording: $id", e)
                _message.value = "Failed to delete recording"
            }
        }
    }

    fun shareAudio(context: Context) {
        viewModelScope.launch {
            val path = _uiState.value.audioPath
            if (path.isEmpty()) return@launch
            val file = File(path)
            val exists = withContext(Dispatchers.IO) { file.exists() }
            if (!exists) {
                _message.value = "Audio file not found"
                return@launch
            }
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "audio/m4a"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, _uiState.value.title)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(intent, "Share audio").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _message.value = "Failed to share: ${e.message}"
            }
        }
    }

    fun shareTranscript(context: Context) {
        val text = buildString {
            appendLine("# ${_uiState.value.title}")
            appendLine()
            if (_uiState.value.summary.isNotEmpty()) {
                appendLine("## Summary")
                appendLine(_uiState.value.summary)
                appendLine()
            }
            appendLine("## Transcript")
            appendLine(rawTranscript)
        }
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, _uiState.value.title)
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share transcript").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _message.value = "Failed to share: ${e.message}"
        }
    }

    fun shareBoth(context: Context) {
        viewModelScope.launch {
            val path = _uiState.value.audioPath
            if (path.isEmpty()) return@launch
            val file = File(path)
            val exists = withContext(Dispatchers.IO) { file.exists() }
            if (!exists) {
                _message.value = "Audio file not found"
                return@launch
            }
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                val text = buildString {
                    appendLine("# ${_uiState.value.title}")
                    appendLine()
                    if (_uiState.value.summary.isNotEmpty()) {
                        appendLine("## Summary")
                        appendLine(_uiState.value.summary)
                        appendLine()
                    }
                    appendLine("## Transcript")
                    appendLine(rawTranscript)
                }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "audio/m4a"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, _uiState.value.title)
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(intent, "Share recording").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _message.value = "Failed to share: ${e.message}"
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        player?.release()
        player = null
    }
}
