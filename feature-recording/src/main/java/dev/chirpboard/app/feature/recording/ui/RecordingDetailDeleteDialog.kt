package dev.chirpboard.app.feature.recording.ui

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog

@Composable
internal fun RecordingDetailDeleteDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    onDeleteConfirmed: () -> Unit,
) {
    if (!visible) {
        return
    }

    AnimatedAlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Delete recording?") },
        text = {
            Text("This action cannot be undone. The audio file and any transcription will be permanently deleted.")
        },
        confirmButton = {
            TextButton(
                onClick = onDeleteConfirmed,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        },
    )
}
