package dev.chirpboard.app.core.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class RecordingPlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val exoPlayer =
            ExoPlayer.Builder(this)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(),
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                // Recordings are long and usually played with the screen off; without the
                // wakelock the CPU can suspend mid-playback and the audio stutters or stalls.
                // The lock is held only while playing (WAKE_LOCK comes from feature-recording).
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build()
                .also { player ->
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    player.addListener(
                        object : Player.Listener {
                            override fun onTimelineChanged(
                                timeline: Timeline,
                                reason: Int,
                            ) {
                                // The controller clears the queue when the user dismisses
                                // playback. Media3 starts this service for real playback but
                                // never stops it again, so an empty queue while paused is the
                                // signal to shut down (the controller unbinds right after).
                                if (timeline.isEmpty && !player.playWhenReady) {
                                    stopSelf()
                                }
                            }
                        },
                    )
                }
        player = exoPlayer
        mediaSession =
            MediaSession.Builder(this, exoPlayer)
                .setId(SESSION_ID)
                .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val player = player ?: return
        if (!player.playWhenReady) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        player = null
        super.onDestroy()
    }

    companion object {
        const val SESSION_ID = "chirp-recording-playback"
    }
}
