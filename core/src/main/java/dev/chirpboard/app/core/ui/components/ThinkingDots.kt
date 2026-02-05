package dev.chirpboard.app.core.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 3-dot "thinking" animation for processing state.
 *
 * Displays three dots arranged horizontally that bounce vertically
 * with staggered timing, creating a "typing" or "thinking" effect.
 *
 * @param modifier Modifier for the composable
 * @param dotSize Diameter of each dot
 * @param spacing Horizontal spacing between dots
 * @param color Color of the dots (defaults to primary @ 60% alpha)
 */
@Composable
fun ThinkingDots(
    modifier: Modifier = Modifier,
    dotSize: Dp = 6.dp,
    spacing: Dp = 8.dp,
    color: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking_dots")

    val bounceHeight = 4.dp

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val delay = index * 200

            val offsetY by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -bounceHeight.value,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 400,
                        delayMillis = delay
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_bounce_$index"
            )

            Canvas(
                modifier = Modifier
                    .size(dotSize)
                    .offset(y = offsetY.dp)
            ) {
                drawCircle(color = color)
            }
        }
    }
}
