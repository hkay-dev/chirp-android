package dev.chirpboard.app.core.ui.playback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.playback.R as PlaybackR
import dev.chirpboard.app.core.playback.RecordingPlaybackState
import androidx.compose.material3.minimumInteractiveComponentSize
import dev.chirpboard.app.core.ui.components.ChirpPill
import dev.chirpboard.app.core.ui.motion.PushDownReveal
import dev.chirpboard.app.core.ui.motion.animatePushDownLayout
import java.util.UUID

@Composable
fun RecordingFullPlayer(
    state: RecordingPlaybackState,
    screenRecordingId: UUID,
    screenTitle: String,
    alternateAudioNotice: String?,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The speed control talks to the singleton controller directly so existing call
    // sites keep their state-and-callbacks contract unchanged.
    val playbackController = rememberRecordingPlaybackController()
    val displayState =
        if (state.recordingId == screenRecordingId) {
            state
        } else {
            RecordingPlaybackState(
                recordingId = screenRecordingId,
                title = screenTitle,
                playbackSpeed = state.playbackSpeed,
            )
        }

    val isLoading = displayState.isLoading
    val isError = displayState.errorMessage != null
    val isPlaying = displayState.isPlaying
    val controlsEnabled = !isLoading && !isError && displayState.durationMs > 0

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .animatePushDownLayout()
                .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PushDownReveal(visible = alternateAudioNotice != null) {
            alternateAudioNotice?.let { message ->
                PlaybackNoticeBanner(message = message)
            }
        }

        PushDownReveal(visible = displayState.noticeMessage != null) {
            displayState.noticeMessage?.let { message ->
                PlaybackNoticeBanner(message = message)
            }
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            PlaybackTransportRow(
                isLoading = isLoading,
                isError = isError,
                isPlaying = isPlaying,
                controlsEnabled = controlsEnabled,
                onPlayPause = onPlayPause,
                onSkipBackward = onSkipBackward,
                onSkipForward = onSkipForward,
                playButtonSize = 44.dp,
            )
            PlaybackSpeedChip(
                playbackSpeed = displayState.playbackSpeed,
                onCycleSpeed = playbackController::cyclePlaybackSpeed,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        PlaybackTimelineRow(
            positionMs = displayState.positionMs,
            durationMs = displayState.durationMs,
            enabled = controlsEnabled || isPlaying,
            onSeek = onSeek,
        )
    }
}

@Composable
private fun PlaybackSpeedChip(
    playbackSpeed: Float,
    onCycleSpeed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val speedText = formatPlaybackSpeed(playbackSpeed)
    val description = stringResource(PlaybackR.string.playback_speed_description, speedText)
    ChirpPill(
        label = stringResource(PlaybackR.string.playback_speed_label, speedText),
        onClick = onCycleSpeed,
        // The capsule itself is only ~45x32dp. This keeps that visual size while giving the
        // tap target the 48dp minimum the rest of the transport already meets.
        modifier =
            modifier
                .minimumInteractiveComponentSize()
                .semantics { contentDescription = description },
    )
}

/** "1", "1.25", "0.75" — no trailing zeros so the chip stays compact. */
internal fun formatPlaybackSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) {
        speed.toInt().toString()
    } else {
        speed.toString().trimEnd('0').trimEnd('.')
    }

fun shouldShowGlobalMiniPlayer(
    playbackState: RecordingPlaybackState,
    currentRoute: String?,
    studioRecordingId: String?,
): Boolean {
    // Error states stay visible until dismissed (close = stop()); without this a
    // playback failure would silently hide the bar mid-message (AUD-12).
    if (!playbackState.isActive && !playbackState.isLoading && playbackState.errorMessage == null) return false
    // Opening a Studio prepares playback without playing; a session the user never
    // started must not put a "now playing" bar on every other screen.
    // Errors are no exception: a prepare that failed because the file is gone used to pin an
    // error bar to every route for a session the user never started (and the Studio screen
    // that triggered it already shows the message). The play/refusal paths set the flag on
    // their own error states, so a failure the user asked for still gets its bar.
    if (!playbackState.hasStartedPlayback) return false
    // Capture and playback UIs never co-exist: the Record screen owns the bottom edge
    // (action row + auto-started capture) and the controller refuses play() during a live
    // recording anyway (AUD-10), so the global bar hides for the whole record route. Any
    // still-active playback resumes its bar as soon as the user navigates back.
    if (currentRoute?.substringBefore('?') == RECORD_ROUTE_BASE) return false
    if (studioRecordingId == null || currentRoute?.contains(STUDIO_ROUTE_BASE) != true) return true
    return playbackState.recordingId?.toString() != studioRecordingId
}

/** Base of the app's Record destination route ("record?autoStart=…&profileId=…"). */
private const val RECORD_ROUTE_BASE = "record"

/** Base of the app's Processing Studio destination route ("processing_studio/{recordingId}"). */
private const val STUDIO_ROUTE_BASE = "processing_studio"
