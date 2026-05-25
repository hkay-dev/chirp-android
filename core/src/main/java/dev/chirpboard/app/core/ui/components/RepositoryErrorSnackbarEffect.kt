package dev.chirpboard.app.core.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
fun RepositoryErrorSnackbarEffect(
    errorMessage: String?,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onDismiss()
        }
    }
}
