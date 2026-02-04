package dev.chirpboard.app.feature.recording.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val SecondaryButtonSize = 64.dp
private val SecondaryIconSize = 28.dp

/**
 * Done button - saves recording and triggers transcription.
 */
@Composable
fun DoneButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = SecondaryButtonSize,
    iconSize: Dp = SecondaryIconSize
) {
    val containerColor = MaterialTheme.colorScheme.secondaryContainer
    val contentColor = MaterialTheme.colorScheme.onSecondaryContainer

    Surface(
        onClick = onClick,
        modifier = modifier.size(buttonSize),
        shape = CircleShape,
        color = containerColor,
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Done - save and transcribe",
                modifier = Modifier.size(iconSize),
                tint = contentColor,
            )
        }
    }
}

/**
 * Cancel button - discards the current recording.
 */
@Composable
fun CancelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = SecondaryButtonSize,
    iconSize: Dp = SecondaryIconSize
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val contentColor = MaterialTheme.colorScheme.error

    Surface(
        onClick = onClick,
        modifier = modifier.size(buttonSize),
        shape = CircleShape,
        color = containerColor,
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Cancel recording",
                modifier = Modifier.size(iconSize),
                tint = contentColor,
            )
        }
    }
}

/**
 * Restart button - discards current recording and starts fresh.
 */
@Composable
fun RestartButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = SecondaryButtonSize,
    iconSize: Dp = SecondaryIconSize
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        onClick = onClick,
        modifier = modifier.size(buttonSize),
        shape = CircleShape,
        color = containerColor,
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Restart recording",
                modifier = Modifier.size(iconSize),
                tint = contentColor,
            )
        }
    }
}

/**
 * Row of secondary control buttons for the recording screen.
 * Shows Cancel, Restart, and Done buttons.
 */
@Composable
fun SecondaryButtonsRow(
    onDone: () -> Unit,
    onCancel: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CancelButton(onClick = onCancel)
        RestartButton(onClick = onRestart)
        DoneButton(onClick = onDone)
    }
}
