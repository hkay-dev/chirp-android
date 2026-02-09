package dev.chirpboard.app.feature.recording.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val MainButtonSize = 120.dp
private val MainIconSize = 48.dp

/**
 * Recording UI state for the main action button.
 */
sealed class RecordingUiState {
    data object Idle : RecordingUiState()
    data object Recording : RecordingUiState()
    data object Paused : RecordingUiState()
}

/**
 * Main action button for recording control.
 * 
 * Features:
 * - Large 120dp circular button
 * - Pulsing animation during recording (900ms cycle, 1.0 to 1.06 scale)
 * - Smooth color transitions between states
 * - Icon crossfade for state changes
 * 
 * @param state Current recording UI state
 * @param recordingColor Color to use when recording/paused (typically error/red)
 * @param onStartRecording Called when user taps to start recording (from Idle state)
 * @param onPause Called when user taps to pause recording (from Recording state)
 * @param onResume Called when user taps to resume from paused
 * @param modifier Optional modifier
 * @param buttonSize Size of the button (default 120.dp)
 * @param iconSize Size of the icon (default 48.dp)
 */
@Composable
fun MainActionButton(
    state: RecordingUiState,
    recordingColor: Color,
    onStartRecording: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = MainButtonSize,
    iconSize: Dp = MainIconSize
) {
    val isRecording = state is RecordingUiState.Recording
    val isPaused = state is RecordingUiState.Paused
    val isActive = isRecording || isPaused

    // Pulsing animation for recording state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.06f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    // Smooth color transitions
    val idleColor = MaterialTheme.colorScheme.primaryContainer
    val idleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val activeRecordingColor = recordingColor

    val containerColor by animateColorAsState(
        targetValue = if (isActive) activeRecordingColor else idleColor,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "containerColor",
    )

    val contentColor by animateColorAsState(
        targetValue = if (isActive) Color.White else idleContentColor,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "contentColor",
    )

    // Animated elevation
    val elevationFloat by animateFloatAsState(
        targetValue = if (isActive) 12f else 8f,
        animationSpec = tween(300),
        label = "elevation",
    )
    val elevation = elevationFloat.dp

    val onClick = when {
        isRecording -> onPause
        isPaused -> onResume
        else -> onStartRecording
    }

    // Button with prominent shadow
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(buttonSize)
            .scale(if (isRecording && !isPaused) pulseScale else 1f),
        shape = CircleShape,
        color = containerColor,
        shadowElevation = elevation,
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            // Crossfade between icons for smooth transition
            Crossfade(
                targetState = state::class,
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                label = "iconCrossfade",
            ) { stateClass ->
                val (icon, description) = when (stateClass) {
                    RecordingUiState.Recording::class -> Icons.Default.Pause to "Pause Recording"
                    RecordingUiState.Paused::class -> Icons.Default.PlayArrow to "Resume Recording"
                    else -> Icons.Default.Mic to "Start Recording"
                }

                Icon(
                    imageVector = icon,
                    contentDescription = description,
                    modifier = Modifier.size(iconSize),
                    tint = contentColor,
                )
            }
        }
    }
}
