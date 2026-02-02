package dev.parakeeboard.app.feature.recording.audio

import android.content.Context
import android.media.MediaPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State of audio playback.
 */
sealed class PlaybackState {
    object Idle : PlaybackState()
    data class Loading(val filePath: String) : PlaybackState()
    data class Playing(
        val filePath: String,
        val positionMs: Long,
        val durationMs: Long
    ) : PlaybackState()
    data class Paused(
        val filePath: String,
        val positionMs: Long,
        val durationMs: Long
    ) : PlaybackState()
    data class Error(val message: String) : PlaybackState()
}

/**
 * Manages audio playback for recordings.
 */
@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    
    private var currentFilePath: String? = null
    
    /**
     * Load and optionally start playing an audio file.
     */
    fun load(filePath: String, autoPlay: Boolean = false) {
        // If same file, just toggle play/pause
        if (filePath == currentFilePath && mediaPlayer != null) {
            if (autoPlay) play() else pause()
            return
        }
        
        // Stop current playback
        release()
        
        _state.value = PlaybackState.Loading(filePath)
        currentFilePath = filePath
        
        try {
            val file = File(filePath)
            if (!file.exists()) {
                _state.value = PlaybackState.Error("Audio file not found")
                return
            }
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                setOnPreparedListener { mp ->
                    val duration = mp.duration.toLong()
                    _state.value = PlaybackState.Paused(filePath, 0, duration)
                    if (autoPlay) {
                        play()
                    }
                }
                setOnCompletionListener {
                    val duration = it.duration.toLong()
                    _state.value = PlaybackState.Paused(filePath, duration, duration)
                    progressJob?.cancel()
                }
                setOnErrorListener { _, what, extra ->
                    _state.value = PlaybackState.Error("Playback error: $what/$extra")
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            _state.value = PlaybackState.Error("Failed to load audio: ${e.message}")
        }
    }
    
    /**
     * Start or resume playback.
     */
    fun play() {
        val mp = mediaPlayer ?: return
        val path = currentFilePath ?: return
        
        try {
            mp.start()
            startProgressUpdates(path, mp.duration.toLong())
        } catch (e: Exception) {
            _state.value = PlaybackState.Error("Failed to play: ${e.message}")
        }
    }
    
    /**
     * Pause playback.
     */
    fun pause() {
        val mp = mediaPlayer ?: return
        val path = currentFilePath ?: return
        
        try {
            mp.pause()
            progressJob?.cancel()
            _state.value = PlaybackState.Paused(
                filePath = path,
                positionMs = mp.currentPosition.toLong(),
                durationMs = mp.duration.toLong()
            )
        } catch (e: Exception) {
            _state.value = PlaybackState.Error("Failed to pause: ${e.message}")
        }
    }
    
    /**
     * Toggle play/pause.
     */
    fun togglePlayPause() {
        val currentState = _state.value
        when (currentState) {
            is PlaybackState.Playing -> pause()
            is PlaybackState.Paused -> play()
            else -> {}
        }
    }
    
    /**
     * Seek to a position.
     */
    fun seekTo(positionMs: Long) {
        val mp = mediaPlayer ?: return
        val path = currentFilePath ?: return
        
        try {
            mp.seekTo(positionMs.toInt())
            
            val currentState = _state.value
            when (currentState) {
                is PlaybackState.Playing -> {
                    _state.value = PlaybackState.Playing(
                        filePath = path,
                        positionMs = positionMs,
                        durationMs = mp.duration.toLong()
                    )
                }
                is PlaybackState.Paused -> {
                    _state.value = PlaybackState.Paused(
                        filePath = path,
                        positionMs = positionMs,
                        durationMs = mp.duration.toLong()
                    )
                }
                else -> {}
            }
        } catch (e: Exception) {
            _state.value = PlaybackState.Error("Failed to seek: ${e.message}")
        }
    }
    
    /**
     * Skip forward by the given amount.
     */
    fun skipForward(amountMs: Long = 10_000) {
        val mp = mediaPlayer ?: return
        val newPosition = (mp.currentPosition + amountMs).coerceAtMost(mp.duration.toLong())
        seekTo(newPosition)
    }
    
    /**
     * Skip backward by the given amount.
     */
    fun skipBackward(amountMs: Long = 10_000) {
        val mp = mediaPlayer ?: return
        val newPosition = (mp.currentPosition - amountMs).coerceAtLeast(0)
        seekTo(newPosition)
    }
    
    /**
     * Release resources.
     */
    fun release() {
        progressJob?.cancel()
        progressJob = null
        
        mediaPlayer?.apply {
            try {
                if (isPlaying) stop()
                release()
            } catch (e: Exception) {
                // Ignore
            }
        }
        mediaPlayer = null
        currentFilePath = null
        _state.value = PlaybackState.Idle
    }
    
    private fun startProgressUpdates(filePath: String, durationMs: Long) {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && mediaPlayer?.isPlaying == true) {
                val position = mediaPlayer?.currentPosition?.toLong() ?: 0
                _state.value = PlaybackState.Playing(filePath, position, durationMs)
                delay(100) // Update every 100ms
            }
        }
    }
}
