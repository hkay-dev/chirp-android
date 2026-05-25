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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.util.formatAsDuration

@Composable
fun RecordingTimer(
    recordingState: RecordingState,
    isRecording: Boolean,
    textStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.displayLarge.copy(
        fontSize = 72.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = 2.sp,
        fontFeatureSettings = "tnum"
    ),
    modifier: Modifier = Modifier,
) {
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var previousSegmentsMs by remember { mutableLongStateOf(0L) }

    val textColor by animateColorAsState(
        targetValue = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
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
                    elapsedMs = previousSegmentsMs + (System.currentTimeMillis() - segmentStart)
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

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = elapsedMs.formatAsDuration(),
            style = textStyle,
            color = textColor,
        )
        Text(
            text = "DURATION",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            letterSpacing = 1.sp
        )
    }
}