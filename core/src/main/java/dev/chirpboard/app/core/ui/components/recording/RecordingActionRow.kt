package dev.chirpboard.app.core.ui.components.recording

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun RecordingActionRow(
    isRecording: Boolean,
    isPaused: Boolean,
    onTogglePausePlay: () -> Unit,
    onStopRecording: () -> Unit,
    onRestartRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isActive = isRecording || isPaused

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 32.dp),
    ) {
        FilledTonalIconButton(
            onClick = onTogglePausePlay,
            modifier =
                Modifier
                    .size(64.dp)
                    .align(Alignment.CenterStart),
        ) {
            Icon(
                imageVector = if (isPaused || !isActive) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
        }

        Button(
            onClick = onStopRecording,
            enabled = isActive,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .width(160.dp)
                    .height(80.dp),
            shape = RoundedCornerShape(28.dp),
        ) {
            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text("Done", style = MaterialTheme.typography.titleLarge)
        }

        FilledTonalIconButton(
            onClick = onRestartRecording,
            enabled = isActive,
            modifier =
                Modifier
                    .size(64.dp)
                    .align(Alignment.CenterEnd),
            colors =
                IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(32.dp))
        }
    }
}