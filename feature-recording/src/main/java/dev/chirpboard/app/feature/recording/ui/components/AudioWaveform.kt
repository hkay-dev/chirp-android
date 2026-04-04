package dev.chirpboard.app.feature.recording.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Audio waveform visualization component with smooth 60fps animation.
 *
 * Displays real-time audio amplitude as vertical bars when recording,
 * or a subtle dotted line when idle/paused.
 *
 * Each bar smoothly animates to its target height using spring physics
 * for fluid, high-framerate visualization.
 *
 * @param amplitudes List of amplitude values (0-1) to display
 * @param isActive Whether actively recording (shows waveform vs dotted line)
 * @param color Color for the waveform bars
 * @param modifier Modifier for the component
 * @param barCount Number of bars to display (default 50)
 * @param minBarHeight Minimum height for bars (when amplitude is 0)
 * @param maxBarHeight Maximum height for bars (when amplitude is 1)
 */
@Composable
fun AudioWaveform(
    amplitudes: List<Float>,
    isActive: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 50,
    minBarHeight: Dp = 4.dp,
    maxBarHeight: Dp = 64.dp,
) {
    // Animate color changes smoothly
    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(400, easing = EaseInOut),
        label = "waveformColor",
    )

    // Animate between active (1) and idle (0) states
    val activeAlpha by animateFloatAsState(
        targetValue = if (isActive && amplitudes.isNotEmpty()) 1f else 0f,
        animationSpec = tween(300, easing = EaseInOut),
        label = "activeAlpha",
    )

    // Prepare target amplitude values - pad to barCount if needed
    val targetAmplitudes =
        remember(amplitudes, barCount) {
            if (amplitudes.isEmpty()) {
                List(barCount) { 0f }
            } else if (amplitudes.size >= barCount) {
                amplitudes.takeLast(barCount)
            } else {
                List(barCount - amplitudes.size) { 0f } + amplitudes
            }
        }

    // Animated bar heights using stable Animatable list
    // Single animation controller prevents 50 independent recomposition triggers
    val animatables =
        remember(barCount) {
            List(barCount) { Animatable(0f) }
        }

    // Drive all bar animations from single LaunchedEffect
    LaunchedEffect(targetAmplitudes) {
        animatables.forEachIndexed { index, animatable ->
            val target = targetAmplitudes.getOrElse(index) { 0f }
            launch {
                animatable.animateTo(
                    targetValue = target,
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                )
            }
        }
    }

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(maxBarHeight + 16.dp)
                .graphicsLayer(), // GPU acceleration for smooth 120Hz rendering
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val centerY = canvasHeight / 2

        val barWidth = 3.dp.toPx()
        val barSpacing = (canvasWidth - (barCount * barWidth)) / (barCount + 1)
        val minHeightPx = minBarHeight.toPx()
        val maxHeightPx = maxBarHeight.toPx()

        // Draw dotted line when not active (fades out when waveform appears)
        if (activeAlpha < 1f) {
            val dottedAlpha = (1f - activeAlpha) * 0.3f
            drawLine(
                color = animatedColor.copy(alpha = dottedAlpha),
                start = Offset(barSpacing, centerY),
                end = Offset(canvasWidth - barSpacing, centerY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect =
                    PathEffect.dashPathEffect(
                        floatArrayOf(6.dp.toPx(), 8.dp.toPx()),
                        0f,
                    ),
            )
        }

        // Draw waveform bars with animated heights
        if (activeAlpha > 0f) {
            animatables.forEachIndexed { index, animatable ->
                // Scale amplitude by activeAlpha for smooth fade in/out
                val scaledAmplitude = animatable.value * activeAlpha
                val barHeight =
                    (minHeightPx + (scaledAmplitude * (maxHeightPx - minHeightPx)))
                        .coerceIn(minHeightPx, maxHeightPx)

                val x = barSpacing + (index * (barWidth + barSpacing)) + barWidth / 2
                val halfHeight = barHeight / 2

                drawLine(
                    color = animatedColor.copy(alpha = 0.8f * activeAlpha),
                    start = Offset(x, centerY - halfHeight),
                    end = Offset(x, centerY + halfHeight),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
