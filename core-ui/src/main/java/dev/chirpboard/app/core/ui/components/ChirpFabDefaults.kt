package dev.chirpboard.app.core.ui.components

import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

object ChirpFabDefaults {
    val primaryContainerColor: Color
        @Composable get() = MaterialTheme.colorScheme.primaryContainer

    val onPrimaryContainerColor: Color
        @Composable get() = MaterialTheme.colorScheme.onPrimaryContainer

    val secondaryContainerColor: Color
        @Composable get() = MaterialTheme.colorScheme.secondaryContainer

    val onSecondaryContainerColor: Color
        @Composable get() = MaterialTheme.colorScheme.onSecondaryContainer
}

@Composable
fun ChirpPrimaryExtendedFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    icon: @Composable () -> Unit,
    text: @Composable () -> Unit,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        expanded = expanded,
        containerColor = ChirpFabDefaults.primaryContainerColor,
        contentColor = ChirpFabDefaults.onPrimaryContainerColor,
        elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
        icon = icon,
        text = text,
    )
}

@Composable
fun ChirpPrimaryFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = ChirpFabDefaults.primaryContainerColor,
        contentColor = ChirpFabDefaults.onPrimaryContainerColor,
        content = content,
    )
}
