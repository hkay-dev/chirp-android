package dev.chirpboard.app.feature.recording.audio

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

    data class Loading(
        val filePath: String,
    ) : PlaybackState()

    data class Playing(
        val filePath: String,
        val positionMs: Long,
        val durationMs: Long,
    ) : PlaybackState()

    data class Paused(
        val filePath: String,
        val positionMs: Long,
        val durationMs: Long,
    ) : PlaybackState()

    data class Error(
        val message: String,
    ) : PlaybackState()
}

/**
 * Manages audio playback for recordings.
 */
@Singleton
class AudioPlayer
    @Inject
    constructor() {
        private var mediaPlayer: MediaPlayer? = null
        private var progressJob: Job? = null
        private val scope = CoroutineScope(Dispatchers.Main)

        private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        val state: StateFlow<PlaybackState> = _state.asStateFlow()

        private var currentFilePath: String? = null
        private var isPrepared: Boolean = false

        /**
         * Load an audio file for playback.
         */
        fun load(
            filePath: String,
            autoPlay: Boolean = false,
        ) {
            // Release any existing player
            release()

            // Check if file exists first
            val file = java.io.File(filePath)
            if (!file.exists()) {
                _state.value = PlaybackState.Error("Audio file not found")
                return
            }
            if (!file.canRead()) {
                _state.value = PlaybackState.Error("Cannot read audio file")
                return
            }

            currentFilePath = filePath
            _state.value = PlaybackState.Loading(filePath)

            try {
                val player = MediaPlayer()
                mediaPlayer = player
                player.apply {
                    setDataSource(filePath)
                    setOnPreparedListener { mp ->
                        isPrepared = true
                        val duration = mp.duration.toLong()
                        _state.value = PlaybackState.Paused(filePath, 0, duration)
                        if (autoPlay) {
                            play()
                        }
                    }
                    setOnCompletionListener {
                        val duration = if (isPrepared) it.duration.toLong() else 0
                        _state.value = PlaybackState.Paused(filePath, duration, duration)
                        progressJob?.cancel()
                    }
                    setOnErrorListener { _, what, extra ->
                        isPrepared = false
                        android.util.Log.e("AudioPlayer", "MediaPlayer error: what=$what extra=$extra path=$filePath")
                        val errorMsg =
                            when (what) {
                                MediaPlayer.MEDIA_ERROR_UNKNOWN -> {
                                    // extra gives more detail for MEDIA_ERROR_UNKNOWN
                                    when (extra) {
                                        -2147483648 -> "Unsupported audio format"
                                        -1004 -> "File not found"
                                        -1007 -> "Connection timeout"
                                        else -> "Unable to play audio"
                                    }
                                }

                                MediaPlayer.MEDIA_ERROR_SERVER_DIED -> {
                                    "Media server error"
                                }

                                else -> {
                                    "Playback error"
                                }
                            }
                        _state.value = PlaybackState.Error(errorMsg)
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                mediaPlayer?.release()
                mediaPlayer = null
                isPrepared = false
                android.util.Log.e("AudioPlayer", "Failed to load audio", e)
                _state.value = PlaybackState.Error("Failed to load audio")
            }
        }

        /**
         * Start or resume playback.
         */
        fun play() {
            val mp = mediaPlayer ?: return
            val path = currentFilePath ?: return

            if (!isPrepared) {
                _state.value = PlaybackState.Error("Audio not ready yet")
                return
            }

            try {
                mp.start()
                startProgressUpdates(path, mp.duration.toLong())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.value = PlaybackState.Error("Failed to play: ${e.message}")
            }
        }

        /**
         * Pause playback.
         */
        fun pause() {
            val mp = mediaPlayer ?: return
            val path = currentFilePath ?: return

            if (!isPrepared) return

            try {
                mp.pause()
                progressJob?.cancel()
                _state.value =
                    PlaybackState.Paused(
                        filePath = path,
                        positionMs = mp.currentPosition.toLong(),
                        durationMs = mp.duration.toLong(),
                    )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.value = PlaybackState.Error("Failed to pause: ${e.message}")
            }
        }

        /**
         * Toggle play/pause.
         */
        fun togglePlayPause() {
            val currentState = _state.value
            when (currentState) {
                is PlaybackState.Playing -> {
                    pause()
                }

                is PlaybackState.Paused -> {
                    play()
                }

                else -> {}
            }
        }

        /**
         * Seek to a position.
         */
        fun seekTo(positionMs: Long) {
            val mp = mediaPlayer ?: return
            val path = currentFilePath ?: return

            if (!isPrepared) return

            try {
                mp.seekTo(positionMs.toInt())

                val currentState = _state.value
                when (currentState) {
                    is PlaybackState.Playing -> {
                        _state.value =
                            PlaybackState.Playing(
                                filePath = path,
                                positionMs = positionMs,
                                durationMs = mp.duration.toLong(),
                            )
                    }

                    is PlaybackState.Paused -> {
                        _state.value =
                            PlaybackState.Paused(
                                filePath = path,
                                positionMs = positionMs,
                                durationMs = mp.duration.toLong(),
                            )
                    }

                    else -> {}
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
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
            isPrepared = false

            mediaPlayer?.apply {
                try {
                    if (isPlaying) stop()
                    release()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // Ignore
                }
            }
            mediaPlayer = null
            currentFilePath = null
            _state.value = PlaybackState.Idle
        }

        private fun startProgressUpdates(
            filePath: String,
            durationMs: Long,
        ) {
            progressJob?.cancel()
            progressJob =
                scope.launch {
                    while (isActive && mediaPlayer?.isPlaying == true) {
                        val position = mediaPlayer?.currentPosition?.toLong() ?: 0
                        _state.value = PlaybackState.Playing(filePath, position, durationMs)
                        delay(100) // Update every 100ms
                    }
                }
        }
    }
