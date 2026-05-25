package dev.chirpboard.app.core.playback

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingPlaybackController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        private val _state = MutableStateFlow(RecordingPlaybackState())
        val state: StateFlow<RecordingPlaybackState> = _state.asStateFlow()

        private var controller: MediaController? = null
        private val connectMutex = Mutex()
        private var positionJob: Job? = null

        private val playerListener =
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    syncFromPlayer()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    syncFromPlayer()
                    if (isPlaying) {
                        startPositionUpdates()
                    } else {
                        positionJob?.cancel()
                    }
                }

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    syncFromPlayer()
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e(TAG, "Playback error", error)
                    _state.value =
                        _state.value.copy(
                            isLoading = false,
                            isPlaying = false,
                            errorMessage = error.message ?: "Unable to play audio",
                        )
                }
            }

        fun prepare(
            recordingId: UUID,
            title: String,
            audioPath: String,
        ) {
            if (!validateAudioFile(audioPath, recordingId)) return
            runWithController { player ->
                val currentId = activeRecordingId(player)
                if (currentId == recordingId) {
                    syncFromPlayer()
                    return@runWithController
                }
                _state.value =
                    RecordingPlaybackState(
                        recordingId = recordingId,
                        title = title,
                        audioPath = audioPath,
                        isLoading = true,
                    )
                player.setMediaItem(buildMediaItem(recordingId, title, audioPath))
                player.prepare()
                player.playWhenReady = false
                syncFromPlayer()
            }
        }

        fun play(
            recordingId: UUID,
            title: String,
            audioPath: String,
        ) {
            if (!validateAudioFile(audioPath, recordingId)) return
            runWithController { player ->
                val currentId = activeRecordingId(player)
                if (currentId == recordingId) {
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        player.play()
                    }
                    syncFromPlayer()
                    return@runWithController
                }

                _state.value =
                    RecordingPlaybackState(
                        recordingId = recordingId,
                        title = title,
                        audioPath = audioPath,
                        isLoading = true,
                    )
                player.setMediaItem(buildMediaItem(recordingId, title, audioPath))
                player.prepare()
                player.play()
                syncFromPlayer()
            }
        }

        fun togglePlayPause() {
            val current = _state.value
            val recordingId = current.recordingId ?: return
            val audioPath = current.audioPath ?: return
            if (current.isPlaying) {
                controller?.pause()
                syncFromPlayer()
            } else {
                play(recordingId, current.title, audioPath)
            }
        }

        fun pause() {
            controller?.pause()
            syncFromPlayer()
        }

        fun seekTo(positionMs: Long) {
            controller?.seekTo(positionMs.coerceAtLeast(0L))
            syncFromPlayer()
        }

        fun skipForward(amountMs: Long = SKIP_MS) {
            val player = controller ?: return
            seekTo(player.currentPosition + amountMs)
        }

        fun skipBackward(amountMs: Long = SKIP_MS) {
            val player = controller ?: return
            seekTo((player.currentPosition - amountMs).coerceAtLeast(0L))
        }

        fun stop() {
            positionJob?.cancel()
            controller?.run {
                pause()
                clearMediaItems()
            }
            _state.value = RecordingPlaybackState()
        }

        fun onStudioOpened(
            recordingId: UUID,
            title: String,
            audioPath: String,
        ) {
            val current = _state.value
            if (current.recordingId == null || current.recordingId == recordingId) {
                prepare(recordingId, title, audioPath)
            }
        }

        private fun validateAudioFile(
            audioPath: String,
            recordingId: UUID,
        ): Boolean {
            val file = File(audioPath)
            if (!file.exists() || !file.canRead()) {
                _state.value =
                    RecordingPlaybackState(
                        recordingId = recordingId,
                        audioPath = audioPath,
                        errorMessage = "Audio file not found",
                    )
                return false
            }
            return true
        }

        private fun runWithController(block: (MediaController) -> Unit) {
            scope.launch {
                try {
                    val player =
                        connectMutex.withLock {
                            controller ?: createController().also { controller = it }
                        }
                    block(player)
                } catch (error: Exception) {
                    Log.e(TAG, "Failed to connect playback controller", error)
                    _state.value =
                        _state.value.copy(
                            isLoading = false,
                            errorMessage = "Playback unavailable",
                        )
                }
            }
        }

        private suspend fun createController(): MediaController {
            // MediaController connects via bindService; do not call startForegroundService here.
            // Prepare-only playback never promotes the session to foreground, which would ANR.
            val sessionToken =
                SessionToken(
                    context,
                    ComponentName(context, RecordingPlaybackService::class.java),
                )
            return MediaController.Builder(context, sessionToken)
                .buildAsync()
                .await()
                .also { it.addListener(playerListener) }
        }

        private fun buildMediaItem(
            recordingId: UUID,
            title: String,
            audioPath: String,
        ): MediaItem =
            MediaItem.Builder()
                .setMediaId(recordingId.toString())
                .setUri(audioPath)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .build(),
                )
                .build()

        private fun activeRecordingId(player: MediaController): UUID? =
            player.currentMediaItem?.mediaId?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        private fun syncFromPlayer() {
            val player = controller ?: return
            val mediaItem = player.currentMediaItem
            if (mediaItem == null) {
                if (_state.value.recordingId != null && player.mediaItemCount == 0) {
                    _state.value = RecordingPlaybackState()
                }
                return
            }

            val recordingId = activeRecordingId(player)
            val durationMs = player.duration.coerceAtLeast(0L)
            val positionMs = player.currentPosition.coerceAtLeast(0L)
            val isLoading =
                player.playbackState == Player.STATE_BUFFERING ||
                    (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0)

            _state.value =
                RecordingPlaybackState(
                    recordingId = recordingId,
                    title = mediaItem.mediaMetadata.title?.toString().orEmpty(),
                    audioPath = mediaItem.localConfiguration?.uri?.path ?: _state.value.audioPath,
                    positionMs = positionMs,
                    durationMs = if (durationMs > 0) durationMs else _state.value.durationMs,
                    isPlaying = player.isPlaying,
                    isLoading = isLoading,
                    errorMessage = null,
                )
        }

        private fun startPositionUpdates() {
            positionJob?.cancel()
            positionJob =
                scope.launch {
                    while (isActive) {
                        syncFromPlayer()
                        delay(POSITION_TICK_MS)
                    }
                }
        }

        companion object {
            private const val TAG = "RecordingPlayback"
            private const val SKIP_MS = 10_000L
            private const val POSITION_TICK_MS = 100L
        }
    }
