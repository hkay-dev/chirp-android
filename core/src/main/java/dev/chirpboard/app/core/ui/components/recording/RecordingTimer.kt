package dev.chirpboard.app.core.ui.components.recording

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.util.formatTimeMs

@Composable
fun RecordingTimer(
    recordingState: RecordingState,
    isRecording: Boolean,
    textStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.displayLarge.copy(
        fontSize = 72.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Light,
        letterSpacing = 2.sp,
    ),
    modifier: Modifier = Modifier,
) {
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var previousSegmentsMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(recordingState) {
        when (val state = recordingState) {
            is RecordingState.Starting -> {
                previousSegmentsMs = 0L
                elapsedMs = 0L
            }

            is RecordingState.Recording -> {
                val segmentStart = state.startTimeMs
                while (true) {
                    withFrameMillis {
                        elapsedMs = previousSegmentsMs + (System.currentTimeMillis() - segmentStart)
                    }
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

    Text(
        text = formatTimeMs(elapsedMs),
        modifier = modifier,
        style = textStyle,
        color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}