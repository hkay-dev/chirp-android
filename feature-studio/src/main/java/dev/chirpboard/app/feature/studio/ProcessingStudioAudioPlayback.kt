package dev.chirpboard.app.feature.studio

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class ProcessingStudioAudioPlayback(
    context: Context,
    private val onPlayingChanged: (Boolean) -> Unit,
    private val onDurationReady: (Long) -> Unit,
) {
    private val player: ExoPlayer =
        ExoPlayer.Builder(context).build().apply {
            addListener(
                object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        onPlayingChanged(isPlaying)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            onDurationReady(duration)
                        }
                    }
                },
            )
        }

    val isPlaying: Boolean
        get() = player.isPlaying

    val currentPositionMs: Long
        get() = player.currentPosition

    fun prepare(audioPath: String) {
        player.setMediaItem(MediaItem.fromUri(audioPath))
        player.prepare()
    }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    fun play() {
        player.play()
    }

    fun release() {
        player.release()
    }
}
