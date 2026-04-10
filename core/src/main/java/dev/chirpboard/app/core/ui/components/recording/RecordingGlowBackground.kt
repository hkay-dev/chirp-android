package dev.chirpboard.app.core.ui.components.recording

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun RecordingGlowBackground(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "glowTransition")
    val glowColor = infiniteTransition.animateColor(
        initialValue = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0f),
        targetValue = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = androidx.compose.animation.core.EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "glowColor",
    )

    Canvas(modifier = modifier) {
        val color = glowColor.value
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    color.copy(alpha = 0.15f),
                    color.copy(alpha = 0.35f),
                ),
                startY = 0f,
                endY = size.height
            )
        )
    }
}