package dev.chirpboard.app.core.ui.components.recording

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

private const val GLOW_TWEEN_MS = 1200
private const val GLOW_MID_ALPHA = 0.15f
private const val GLOW_PEAK_ALPHA = 0.35f

@Composable
fun RecordingGlowBackground(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "glowTransition")
    // Animate a single Float and read it inside the draw lambda so the infinite transition
    // invalidates only the draw phase, never composition (no per-vsync recompose).
    val glowProgress =
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(GLOW_TWEEN_MS, easing = EaseInOut),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "glowProgress",
        )

    // errorContainer (start, alpha 0) -> error (peak) matches the prior animateColor endpoints.
    val startColor = MaterialTheme.colorScheme.errorContainer
    val endColor = MaterialTheme.colorScheme.error

    Canvas(modifier = modifier) {
        val color = lerp(startColor, endColor, glowProgress.value)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    color.copy(alpha = GLOW_MID_ALPHA),
                    color.copy(alpha = GLOW_PEAK_ALPHA),
                ),
                startY = 0f,
                endY = size.height
            )
        )
    }
}
