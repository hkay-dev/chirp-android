package dev.chirpboard.app.core.ui.components.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import dev.chirpboard.app.core.ui.theme.ChirpShapes
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.R as CoreR

@Composable
fun RecordingActionRow(
    isRecording: Boolean,
    isPaused: Boolean,
    onTogglePausePlay: () -> Unit,
    onStopRecording: () -> Unit,
    onRestartRecording: () -> Unit,
    modifier: Modifier = Modifier,
    isStopEnabled: Boolean? = null,
    isRestartEnabled: Boolean? = null,
) {
    val isActive = isRecording || isPaused
    val stopEnabled = isStopEnabled ?: isActive
    val restartEnabled = isRestartEnabled ?: isActive

    // A11Y-3: the pause/resume toggle and the destructive restart button are icon-only;
    // without labels TalkBack reads both as just "Button" on the screen's primary controls.
    val toggleDescription =
        when {
            isPaused -> stringResource(CoreR.string.rec_desc_resume_recording)
            isActive -> stringResource(CoreR.string.rec_desc_pause_recording)
            else -> stringResource(CoreR.string.rec_desc_start_recording)
        }
    val toggleStateDescription =
        when {
            isPaused -> stringResource(CoreR.string.rec_state_paused)
            isActive -> stringResource(CoreR.string.rec_state_recording)
            else -> null
        }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalIconButton(
            onClick = onTogglePausePlay,
            modifier = Modifier
                .size(64.dp)
                .semantics {
                    toggleStateDescription?.let { stateDescription = it }
                },
        ) {
            Icon(
                imageVector = if (isPaused || !isActive) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                contentDescription = toggleDescription,
                modifier = Modifier.size(32.dp),
            )
        }

        Button(
            onClick = onStopRecording,
            enabled = stopEnabled,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .height(80.dp),
            shape = ChirpShapes.ExtraLarge,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(stringResource(CoreR.string.rec_done), style = MaterialTheme.typography.titleLarge)
        }

        FilledTonalIconButton(
            onClick = onRestartRecording,
            enabled = restartEnabled,
            modifier = Modifier.size(64.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Icon(
                Icons.Rounded.Refresh,
                contentDescription = stringResource(CoreR.string.rec_desc_start_over),
                modifier = Modifier.size(32.dp),
            )
        }
    }
}