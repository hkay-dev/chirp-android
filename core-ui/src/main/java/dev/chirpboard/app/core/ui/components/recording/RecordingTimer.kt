package dev.chirpboard.app.core.ui.components.recording

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
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
private const val NANOS_PER_MILLISECOND = 1_000_000L

/**
 * Snaps an elapsed-millisecond value down to whole-second granularity. The timer display formats
 * whole seconds, so writing the snapped value keeps 9 of every 10 (100 ms) ticks identical, and
 * Compose skips invalidation for snapshot writes of equal values.
 */
internal fun snapToSecond(elapsedMs: Long): Long = elapsedMs - (elapsedMs % MILLIS_PER_SECOND)

/**
 * Milliseconds to sleep from [rawElapsedMs] until the next whole-second boundary. Always at
 * least 1 ms so the tick loop suspends even when woken exactly on a boundary.
 */
internal fun delayToNextSecondMs(rawElapsedMs: Long): Long =
    MILLIS_PER_SECOND - (rawElapsedMs % MILLIS_PER_SECOND)

/**
 * Elapsed capture time for [recordingState], snapped to whole seconds.
 *
 * Prior-segment time comes from the state itself, so a timer composed fresh mid-session
 * (screen re-entered, row scrolled back) shows the true total across pauses and segment
 * rotations. Every consumer renders whole seconds, so the loop sleeps until the next second
 * boundary instead of polling sub-second.
 *
 * Returns [State] rather than a plain Long: the 1 Hz tick must invalidate only the scope that
 * reads `.value` (the duration text), not the caller that hosts it — a home list row reading a
 * plain Long re-executed its title, buttons, pills and reveals once a second for the whole
 * capture.
 */
@Composable
fun rememberRecordingElapsedMs(recordingState: RecordingState): State<Long> {
    val elapsedMs = remember { mutableLongStateOf(0L) }

    LaunchedEffect(recordingState) {
        when (val state = recordingState) {
            is RecordingState.Starting -> elapsedMs.longValue = 0L

            is RecordingState.Recording ->
                while (true) {
                    // Monotonic base: a wall-clock correction (NTP sync, manual change)
                    // mid-recording must not jump the on-screen timer.
                    val rawMs =
                        state.accumulatedBeforeSegmentMs +
                            (System.nanoTime() / NANOS_PER_MILLISECOND - state.startMonotonicMs)
                    elapsedMs.longValue = snapToSecond(rawMs)
                    delay(delayToNextSecondMs(rawMs))
                }

            is RecordingState.Paused -> elapsedMs.longValue = state.accumulatedMs

            is RecordingState.Idle -> elapsedMs.longValue = 0L

            else -> Unit
        }
    }

    return elapsedMs
}

/**
 * Whole minutes and leftover seconds for the spoken (TalkBack) duration, e.g. 72_000 ms ->
 * (1, 12) -> "1 minute 12 seconds". Pure so the boundary math is JVM-testable.
 */
internal fun accessibleDurationParts(elapsedMs: Long): Pair<Int, Int> {
    val totalSeconds = elapsedMs / MILLIS_PER_SECOND
    return Pair(
        (totalSeconds / SECONDS_PER_MINUTE).toInt(),
        (totalSeconds % SECONDS_PER_MINUTE).toInt(),
    )
}

@Composable
fun RecordingTimer(
    recordingState: RecordingState,
    isRecording: Boolean,
    textStyle: androidx.compose.ui.text.TextStyle = recordingTimerStyle,
    modifier: Modifier = Modifier,
) {
    val elapsedMs by rememberRecordingElapsedMs(recordingState)

    val textColor by animateColorAsState(
        targetValue = if (isRecording) MaterialTheme.colorScheme.chirpAccents.recordingLive else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(durationMillis = 300),
        label = "timerColor"
    )

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
    val (minutes, seconds) = accessibleDurationParts(elapsedMs)
    val secondsText = pluralStringResource(R.plurals.rec_duration_seconds, seconds, seconds)
    if (minutes == 0) {
        return secondsText
    }
    val minutesText = pluralStringResource(R.plurals.rec_duration_minutes, minutes, minutes)
    return stringResource(R.string.rec_timer_minutes_seconds, minutesText, secondsText)
}