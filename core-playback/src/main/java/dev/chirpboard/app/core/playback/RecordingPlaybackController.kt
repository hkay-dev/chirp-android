package dev.chirpboard.app.core.playback

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.annotation.VisibleForTesting
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.audio.AudioSettingsStore
import dev.chirpboard.app.core.audio.PLAYBACK_SPEED_OPTIONS
import dev.chirpboard.app.core.recording.RecordingStateManager
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class RecordingPlaybackController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val recordingStateManager: RecordingStateManager,
        private val audioSettingsStore: AudioSettingsStore,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        @VisibleForTesting
        internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

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
                    if (isPlaying) {
                        refreshMutedVolumeNotice()
                    }
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

                override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                    syncFromPlayer()
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Playback error (code=${error.errorCode})", error)
                    _state.value =
                        _state.value.copy(
                            isLoading = false,
                            isPlaying = false,
                            errorMessage = friendlyPlaybackError(error),
                        )
                }
            }

        fun prepare(
            recordingId: UUID,
            title: String,
            audioPath: String,
        ) {
            scope.launch {
                if (!validateAudioFile(audioPath, recordingId)) return@launch
                withConnectedController { player ->
                    val currentId = activeRecordingId(player)
                    if (currentId == recordingId && player.playerError == null) {
                        syncFromPlayer()
                        return@withConnectedController
                    }
                    _state.value =
                        RecordingPlaybackState(
                            recordingId = recordingId,
                            title = title,
                            audioPath = audioPath,
                            isLoading = true,
                            playbackSpeed = _state.value.playbackSpeed,
                        )
                    player.setMediaItem(buildMediaItem(recordingId, title, audioPath))
                    player.prepare()
                    player.playWhenReady = false
                    syncFromPlayer()
                }
            }
        }

        fun play(
            recordingId: UUID,
            title: String,
            audioPath: String,
        ) {
            // AUD-10: the app's own playback must never fire a focus request that kills a
            // live recording (the recorder treats permanent focus loss as stop-with-save).
            // Refuse with a visible message instead; the recorder's focus claim wins.
            if (recordingStateManager.state.value.isActive) {
                refusePlayWhileRecording(recordingId, title, audioPath)
                return
            }
            scope.launch {
                if (!validateAudioFile(audioPath, recordingId)) return@launch
                withConnectedController { player ->
                    // Re-check after the file-validate IO hop: a capture started in that
                    // window must not receive our focus request (it treats the loss as
                    // stop-with-save), so the refusal has to be decided here too.
                    if (recordingStateManager.state.value.isActive) {
                        refusePlayWhileRecording(recordingId, title, audioPath)
                        return@withConnectedController
                    }
                    _state.value = _state.value.copy(hasStartedPlayback = true)
                    val currentId = activeRecordingId(player)
                    if (currentId == recordingId) {
                        when {
                            player.playerError != null -> {
                                // Retry after an error: a play() on an errored player is a
                                // no-op, so rebuild the item and prepare again.
                                _state.value =
                                    _state.value.copy(isLoading = true, errorMessage = null)
                                player.setMediaItem(buildMediaItem(recordingId, title, audioPath))
                                player.prepare()
                                player.play()
                            }
                            player.isPlaying -> player.pause()
                            else -> player.play()
                        }
                        syncFromPlayer()
                        return@withConnectedController
                    }

                    _state.value =
                        RecordingPlaybackState(
                            recordingId = recordingId,
                            title = title,
                            audioPath = audioPath,
                            isLoading = true,
                            playbackSpeed = _state.value.playbackSpeed,
                            hasStartedPlayback = true,
                        )
                    player.setMediaItem(buildMediaItem(recordingId, title, audioPath))
                    player.prepare()
                    player.play()
                    syncFromPlayer()
                }
            }
        }

        private fun refusePlayWhileRecording(
            recordingId: UUID,
            title: String,
            audioPath: String,
        ) {
            _state.value =
                RecordingPlaybackState(
                    recordingId = recordingId,
                    title = title,
                    audioPath = audioPath,
                    errorMessage = context.getString(R.string.playback_blocked_while_recording),
                    playbackSpeed = _state.value.playbackSpeed,
                )
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

        /** Pause when another recording is actively playing (e.g. opening a different Studio). */
        fun pauseIfDifferentRecording(recordingId: UUID) {
            val current = _state.value
            if (current.recordingId != null && current.recordingId != recordingId && current.isPlaying) {
                pause()
            }
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

        /** Applies and persists a playback speed (snapped to the supported options). */
        fun setPlaybackSpeed(speed: Float) {
            val snapped = AudioSettingsStore.nearestPlaybackSpeed(speed)
            _state.value = _state.value.copy(playbackSpeed = snapped)
            runWithController { player ->
                player.setPlaybackSpeed(snapped)
                syncFromPlayer()
            }
            scope.launch {
                runCatching { audioSettingsStore.setPlaybackSpeed(snapped) }
                    .onFailure { error -> Log.w(TAG, "Failed to persist playback speed", error) }
            }
        }

        /** Advances to the next supported speed (0.75 -> 1 -> 1.25 -> 1.5 -> 2 -> 0.75). */
        fun cyclePlaybackSpeed() {
            val options = PLAYBACK_SPEED_OPTIONS
            val current = AudioSettingsStore.nearestPlaybackSpeed(_state.value.playbackSpeed)
            val nextIndex = (options.indexOf(current) + 1) % options.size
            setPlaybackSpeed(options[nextIndex])
        }

        fun stop() {
            positionJob?.cancel()
            _state.value = RecordingPlaybackState(playbackSpeed = _state.value.playbackSpeed)
            // Dismissing playback is the teardown point: release the controller so the
            // bound RecordingPlaybackService (and its ExoPlayer) can actually die.
            // Without this the singleton kept them alive for the rest of the process
            // after the first play. runWithController reconnects on the next use.
            scope.launch {
                connectMutex.withLock {
                    controller?.run {
                        pause()
                        clearMediaItems()
                        removeListener(playerListener)
                        release()
                    }
                    controller = null
                }
            }
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

        // Suspends for the file stats: exists()/canRead() hit disk, and every caller is
        // a tap handler on the main thread. Runs inside runWithController's coroutine.
        private suspend fun validateAudioFile(
            audioPath: String,
            recordingId: UUID,
        ): Boolean {
            val readable =
                withContext(ioDispatcher) {
                    val file = File(audioPath)
                    file.exists() && file.canRead()
                }
            if (!readable) {
                _state.value =
                    RecordingPlaybackState(
                        recordingId = recordingId,
                        audioPath = audioPath,
                        errorMessage = context.getString(R.string.playback_error_file_missing),
                        playbackSpeed = _state.value.playbackSpeed,
                    )
                return false
            }
            return true
        }

        private fun runWithController(block: suspend (MediaController) -> Unit) {
            scope.launch { withConnectedController(block) }
        }

        // The mutex is held across the whole command, not just the connect: stop() tears the
        // controller down under the same lock, so releasing early would let a dismiss slot in
        // between connect and command — the command then runs as a silent no-op on a released
        // controller and the state it wrote (isLoading) can never be corrected, freezing the
        // playback buttons until process death.
        private suspend fun withConnectedController(block: suspend (MediaController) -> Unit) {
            try {
                connectMutex.withLock {
                    val cached = controller
                    // A killed playback service leaves the cached controller disconnected;
                    // every command on it is a silent no-op. Rebuild so playback recovers.
                    if (cached != null && !cached.isConnected) {
                        cached.removeListener(playerListener)
                        cached.release()
                        controller = null
                    }
                    val player = controller ?: createController().also { controller = it }
                    block(player)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Failed to connect playback controller", error)
                _state.value =
                    _state.value.copy(
                        isLoading = false,
                        errorMessage = context.getString(R.string.playback_error_generic),
                    )
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
            val controller =
                MediaController.Builder(context, sessionToken)
                    .buildAsync()
                    .await()
                    .also { it.addListener(playerListener) }
            // Restore the persisted playback speed before anything plays.
            val storedSpeed =
                runCatching { audioSettingsStore.currentPlaybackSpeed() }
                    .getOrDefault(1f)
            runCatching { controller.setPlaybackSpeed(storedSpeed) }
            _state.value = _state.value.copy(playbackSpeed = storedSpeed)
            return controller
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
                // Never reset an error state here: a stray callback with no media items
                // (e.g. after a failed validate) must not wipe the message the user is
                // reading. Errors clear on stop() or a successful new prepare/play.
                if (_state.value.recordingId != null &&
                    player.mediaItemCount == 0 &&
                    _state.value.errorMessage == null
                ) {
                    _state.value = RecordingPlaybackState(playbackSpeed = _state.value.playbackSpeed)
                }
                return
            }

            val recordingId = activeRecordingId(player)
            val durationMs = player.duration.coerceAtLeast(0L)
            val positionMs = player.currentPosition.coerceAtLeast(0L)
            val playerError = player.playerError
            // A fatal error parks the player in STATE_IDLE with the item still queued;
            // that is the error state, not "loading" — and it must not be overwritten by
            // the callbacks Media3 batches together with onPlayerError (ERR-15/AUD-12).
            val isLoading =
                playerError == null &&
                    (
                        player.playbackState == Player.STATE_BUFFERING ||
                            (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0)
                    )
            val errorMessage =
                if (playerError != null) {
                    _state.value.errorMessage ?: friendlyPlaybackError(playerError)
                } else {
                    null
                }

            _state.value =
                RecordingPlaybackState(
                    recordingId = recordingId,
                    title = mediaItem.mediaMetadata.title?.toString().orEmpty(),
                    audioPath = mediaItem.localConfiguration?.uri?.path ?: _state.value.audioPath,
                    positionMs = positionMs,
                    durationMs = if (durationMs > 0) durationMs else _state.value.durationMs,
                    isPlaying = player.isPlaying,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    playbackSpeed = player.playbackParameters.speed,
                    noticeMessage = if (player.isPlaying) _state.value.noticeMessage else null,
                    hasStartedPlayback = _state.value.hasStartedPlayback || player.isPlaying,
                )
        }

        /**
         * Maps Media3's developer-oriented error messages ("Source error",
         * "MediaCodecAudioRenderer error…") to user-readable ones; the raw detail stays
         * in the log (ERR-16).
         */
        private fun friendlyPlaybackError(error: PlaybackException): String {
            val resId =
                when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> R.string.playback_error_file_missing
                    in IO_ERROR_CODE_RANGE -> R.string.playback_error_unreadable
                    in PARSING_ERROR_CODE_RANGE, in DECODING_ERROR_CODE_RANGE -> R.string.playback_error_corrupt
                    else -> R.string.playback_error_generic
                }
            return context.getString(resId)
        }

        /** One-shot "media volume is muted" hint when playback starts inaudible (AUD-21). */
        private fun refreshMutedVolumeNotice() {
            val muted =
                runCatching {
                    context.getSystemService(AudioManager::class.java)
                        ?.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
                }.getOrDefault(false)
            _state.value =
                _state.value.copy(
                    noticeMessage = if (muted) context.getString(R.string.playback_volume_muted) else null,
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
            private val IO_ERROR_CODE_RANGE = 2000..2999
            private val PARSING_ERROR_CODE_RANGE = 3000..3999
            private val DECODING_ERROR_CODE_RANGE = 4000..4999
        }
    }
