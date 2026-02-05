package dev.chirpboard.app.core.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.unit.dp

/**
 * Modifier extension for gentle floating animation (used in empty states).
 *
 * Applies a subtle vertical oscillation effect where the element
 * floats up and down smoothly using EaseInOutSine-like timing.
 *
 * - Y offset oscillates 0dp to 8dp and back
 * - Period: 3000ms
 * - Uses infiniteRepeatable with smooth easing
 */
fun Modifier.floatingAnimation(): Modifier = composed {
    val infiniteTransition = rememberInfiniteTransition(label = "floating_animation")

    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1500,
                easing = androidx.compose.animation.core.EaseInOutSine
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floating_offset"
    )

    this.offset(y = offsetY.dp)
}
