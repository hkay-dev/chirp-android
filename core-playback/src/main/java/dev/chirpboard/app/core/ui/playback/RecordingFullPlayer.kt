package dev.chirpboard.app.core.ui.playback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.playback.RecordingPlaybackState
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
    val displayState =
        if (state.recordingId == screenRecordingId) {
            state
        } else {
            RecordingPlaybackState(
                recordingId = screenRecordingId,
                title = screenTitle,
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
                .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (alternateAudioNotice != null) {
            PlaybackNoticeBanner(message = alternateAudioNotice)
        }

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

        PlaybackTimelineRow(
            positionMs = displayState.positionMs,
            durationMs = displayState.durationMs,
            enabled = controlsEnabled || isPlaying,
            onSeek = onSeek,
        )
    }
}

fun shouldShowGlobalMiniPlayer(
    playbackState: RecordingPlaybackState,
    currentRoute: String?,
    studioRecordingId: String?,
): Boolean {
    if (!playbackState.isActive && !playbackState.isLoading) return false
    if (studioRecordingId == null || currentRoute?.contains("processing_studio") != true) return true
    return playbackState.recordingId?.toString() != studioRecordingId
}
