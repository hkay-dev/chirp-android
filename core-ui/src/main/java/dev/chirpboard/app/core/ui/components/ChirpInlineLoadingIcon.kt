package dev.chirpboard.app.core.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Crossfades between a leading icon and a compact loading spinner for inline button content.
 */
@Composable
fun ChirpInlineLoadingIcon(
    isLoading: Boolean,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
    contentDescription: String? = null,
) {
    Crossfade(
        targetState = isLoading,
        modifier = modifier,
        label = "inlineLoadingIcon",
    ) { loading ->
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}
