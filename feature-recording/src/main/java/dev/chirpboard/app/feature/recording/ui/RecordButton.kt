package dev.chirpboard.app.feature.recording.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.util.formatAsDuration

/**
 * Large FAB-style record button with animation.
 */
@Composable
fun RecordButton(
    recordingState: RecordingState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRecording = recordingState is RecordingState.Recording || 
                      recordingState is RecordingState.Starting
    
    // Pulsing animation when recording
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    val backgroundColor by animateColorAsState(
        targetValue = if (isRecording) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
        label = "backgroundColor"
    )
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // Duration display when recording
        if (recordingState is RecordingState.Recording) {
            val duration = System.currentTimeMillis() - recordingState.startTimeMs
            Text(
                text = duration.formatAsDuration(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // Record button
        LargeFloatingActionButton(
            onClick = onClick,
            modifier = Modifier.scale(scale),
            containerColor = backgroundColor,
            contentColor = Color.White
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (isRecording) "Stop recording" else "Start recording",
                modifier = Modifier.size(36.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Status text
        Text(
            text = when (recordingState) {
                is RecordingState.Idle -> "Tap to record"
                is RecordingState.Starting -> "Starting..."
                is RecordingState.Recording -> "Recording"
                is RecordingState.Stopping -> "Stopping..."
                is RecordingState.Error -> "Error"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Compact record button for inline use.
 */
@Composable
fun CompactRecordButton(
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isRecording) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
        label = "backgroundColor"
    )
    
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = backgroundColor,
        contentColor = Color.White
    ) {
        Icon(
            imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
            contentDescription = if (isRecording) "Stop recording" else "Start recording"
        )
    }
}
