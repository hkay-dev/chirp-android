package dev.chirpboard.app.core.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

/**
 * Reusable confirmation dialog with consistent styling.
 * Use for destructive actions or important confirmations.
 *
 * @param title Dialog title
 * @param message Dialog message/description
 * @param confirmLabel Label for the confirm button
 * @param dismissLabel Label for the dismiss/cancel button
 * @param onConfirm Callback when user confirms
 * @param onDismiss Callback when user dismisses
 * @param isDestructive If true, confirm button uses error color (for delete actions)
 */
@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String = "Confirm",
    dismissLabel: String = "Cancel",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDestructive: Boolean = false
) {
    AnimatedAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            if (isDestructive) {
                TextButton(
                    onClick = onConfirm,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(confirmLabel)
                }
            } else {
                TextButton(onClick = onConfirm) {
                    Text(confirmLabel)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
