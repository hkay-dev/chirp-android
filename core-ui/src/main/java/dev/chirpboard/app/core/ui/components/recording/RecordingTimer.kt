package dev.chirpboard.app.core.ui.components.recording

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.chirpboard.app.core.ui.motion.ChirpMotion
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import dev.chirpboard.app.core.ui.R
import dev.chirpboard.app.core.ui.theme.recordingTimerStyle
import dev.chirpboard.app.core.ui.theme.chirpAccents
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.util.formatAsDuration

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L

/**
 * Snaps an elapsed-millisecond value down to whole-second granularity. The timer display formats
 * whole seconds, so writing the snapped value keeps 9 of every 10 (100 ms) ticks identical, and
 * Compose skips invalidation for snapshot writes of equal values.
 */
internal fun snapToSecond(elapsedMs: Long): Long = elapsedMs - (elapsedMs % MILLIS_PER_SECOND)

@Composable
fun RecordingTimer(
    recordingState: RecordingState,
    isRecording: Boolean,
    textStyle: androidx.compose.ui.text.TextStyle = recordingTimerStyle,
    modifier: Modifier = Modifier,
) {
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var previousSegmentsMs by remember { mutableLongStateOf(0L) }

    val textColor by animateColorAsState(
        targetValue = if (isRecording) MaterialTheme.colorScheme.chirpAccents.recordingLive else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(durationMillis = 300),
        label = "timerColor"
    )

    LaunchedEffect(recordingState) {
        when (val state = recordingState) {
            is RecordingState.Starting -> {
                previousSegmentsMs = 0L
                elapsedMs = 0L
            }

            is RecordingState.Recording -> {
                val segmentStart = state.startTimeMs
                while (true) {
                    elapsedMs = snapToSecond(
                        previousSegmentsMs + (System.currentTimeMillis() - segmentStart)
                    )
                    delay(ChirpMotion.TIMER_TICK_MS)
                }
            }

            is RecordingState.Paused -> {
                previousSegmentsMs = state.accumulatedMs
                elapsedMs = state.accumulatedMs
            }

            is RecordingState.Idle -> {
                previousSegmentsMs = 0L
                elapsedMs = 0L
            }

            else -> Unit
        }
    }

    // A11Y: merge digits + caption into one TalkBack stop with a human-readable duration
    // ("Duration: 1 minute 12 seconds") instead of two nodes reading "zero zero, twelve" and
    // "DURATION". Deliberately NOT a live region — 1 Hz updates would be chatty.
    val accessibleDuration = accessibleDurationText(elapsedMs)
    val timerDescription = stringResource(R.string.rec_timer_duration_desc, accessibleDuration)
    Column(
        modifier =
            modifier.semantics(mergeDescendants = true) {
                contentDescription = timerDescription
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = elapsedMs.formatAsDuration(),
            style = textStyle,
            color = textColor,
        )
        Text(
            text = stringResource(R.string.rec_duration_caption).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            // A11Y-9: 0.7f alpha measured 3.99:1 on the light surface — below the 4.5:1 AA
            // floor for 11sp text. 0.85f keeps the muted look while passing both themes.
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            letterSpacing = 1.sp
        )
    }
}

/** Human-readable elapsed time for screen readers ("5 seconds", "1 minute 12 seconds"). */
@Composable
private fun accessibleDurationText(elapsedMs: Long): String {
    val totalSeconds = elapsedMs / MILLIS_PER_SECOND
    val minutes = (totalSeconds / SECONDS_PER_MINUTE).toInt()
    val seconds = (totalSeconds % SECONDS_PER_MINUTE).toInt()
    val secondsText = pluralStringResource(R.plurals.rec_duration_seconds, seconds, seconds)
    if (minutes == 0) {
        return secondsText
    }
    val minutesText = pluralStringResource(R.plurals.rec_duration_minutes, minutes, minutes)
    return stringResource(R.string.rec_timer_minutes_seconds, minutesText, secondsText)
}