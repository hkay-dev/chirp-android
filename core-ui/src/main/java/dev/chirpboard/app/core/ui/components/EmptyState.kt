package dev.chirpboard.app.core.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Empty state view with customizable icon, message, and optional action.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    animateIcon: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val iconModifier =
            if (animateIcon) {
                val infiniteTransition = rememberInfiniteTransition(label = "empty_float")
                val offsetY =
                    infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 8f,
                        animationSpec =
                            infiniteRepeatable(
                                animation = tween(durationMillis = 3000, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse,
                            ),
                        label = "empty_icon_float",
                    )
                Modifier
                    .size(80.dp)
                    .graphicsLayer { translationY = -offsetY.value }
            } else {
                Modifier.size(80.dp)
            }

        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = iconModifier,
            tint = if (animateIcon) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(24.dp))
            FilledTonalButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}
