package dev.chirpboard.app.core.playback

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
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
        val sessionBuilder = MediaSession.Builder(this, exoPlayer).setId(SESSION_ID)
        // Without a session activity, tapping the media notification (or the lock-screen
        // media controls) does nothing at all. The launch intent is resolved rather than
        // naming the activity so this module keeps no dependency on the app module.
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            sessionBuilder.setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        // A build failure here (a duplicate SESSION_ID while two service instances overlap)
        // used to strand the fully built player, wakelock included, for the process lifetime:
        // onDestroy released it only through the session that never existed.
        mediaSession =
            runCatching { sessionBuilder.build() }
                .onFailure { error ->
                    Log.e(TAG, "Failed to build playback session", error)
                    exoPlayer.release()
                    player = null
                    stopSelf()
                }.getOrNull()
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
        // Released independently: the player must not depend on the session existing.
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val SESSION_ID = "chirp-recording-playback"
        private const val TAG = "RecordingPlaybackSvc"
    }
}
