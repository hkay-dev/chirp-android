package dev.chirpboard.app.core.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Single calm pulse ring animation for voice recognition.
 *
 * Draws a circle that expands from [baseSize] to [expandedSize] while fading out,
 * creating a calm breathing rhythm effect.
 *
 * @param isActive Whether the animation is running
 * @param modifier Modifier for the composable
 * @param baseSize Starting size of the pulse ring
 * @param expandedSize Maximum size of the pulse ring
 * @param color Color of the pulse ring (defaults to primary)
 */
@Composable
fun BreathingPulse(
    isActive: Boolean,
    modifier: Modifier = Modifier,
    baseSize: Dp = 72.dp,
    expandedSize: Dp = 96.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "breathing_pulse")
    val density = LocalDensity.current
    val stroke =
        remember(density) {
            with(density) { Stroke(width = 2.dp.toPx()) }
        }

    val progress =
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 1200),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "pulse_progress",
        )

    if (isActive) {
        Canvas(
            modifier = modifier.size(expandedSize),
        ) {
            val progressValue = progress.value
            val currentSize = baseSize + (expandedSize - baseSize) * progressValue
            val alpha = 0.3f * (1f - progressValue)
            val radius = currentSize.toPx() / 2
            drawCircle(
                color = color.copy(alpha = alpha),
                radius = radius,
                style = stroke,
            )
        }
    }
}
