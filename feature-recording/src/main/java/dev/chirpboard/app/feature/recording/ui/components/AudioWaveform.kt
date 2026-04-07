package dev.chirpboard.app.feature.recording.ui.components

import kotlin.math.pow
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
import kotlinx.collections.immutable.ImmutableList
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
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
    amplitudes: ImmutableList<Float>,
    isActive: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 42,
    minBarHeight: Dp = 4.dp,
    maxBarHeight: Dp = 120.dp,
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

    val offsetAnimatable = remember { Animatable(0f) }
    LaunchedEffect(amplitudes.size) {
        val target = amplitudes.size.toFloat()
        if (amplitudes.isEmpty() || target < offsetAnimatable.value) {
            offsetAnimatable.snapTo(target)
        } else {
            offsetAnimatable.animateTo(
                targetValue = target,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
            )
        }
    }

    val newestAmp = amplitudes.lastOrNull() ?: 0f
    val animatedNewestAmp by animateFloatAsState(
        targetValue = newestAmp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "newestAmp",
    )
    val localDensity = androidx.compose.ui.platform.LocalDensity.current
    val dashEffect = remember(localDensity) {
        with(localDensity) {
            PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 8.dp.toPx()), 0f)
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

        val barWidth = 5.dp.toPx()
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
                pathEffect = dashEffect,
            )
        }

        // Draw waveform bars with animated heights
        if (activeAlpha > 0f) {
            val currentOffset = offsetAnimatable.value
            val points = mutableListOf<Offset>()

            val startIdx = maxOf(0, (currentOffset - barCount - 2).toInt())
            val endIdx = minOf(amplitudes.size, (currentOffset + 2).toInt())

            for (i in startIdx until endIdx) {
                val amp = if (i == amplitudes.lastIndex) animatedNewestAmp else amplitudes[i]
                val scaledAmplitude = amp * activeAlpha
                val boostedAmplitude = (scaledAmplitude.pow(0.7f) * 1.5f)
                val barHeight = (minHeightPx + (boostedAmplitude * (maxHeightPx - minHeightPx)))
                    .coerceIn(minHeightPx, maxHeightPx)

                val distance = currentOffset - i - 1
                val rightmostX = canvasWidth - barSpacing - barWidth / 2
                val x = rightmostX - (distance * (barWidth + barSpacing))

                points.add(Offset(x, barHeight / 2f))
            }

            if (points.size > 1) {
                val path = Path()

                // Top curve
                path.moveTo(points.first().x, centerY - points.first().y)
                for (i in 0 until points.size - 1) {
                    val p0 = points[i]
                    val p1 = points[i + 1]
                    val midX = (p0.x + p1.x) / 2f
                    path.cubicTo(midX, centerY - p0.y, midX, centerY - p1.y, p1.x, centerY - p1.y)
                }

                // Bottom curve (mirrored, moving right to left)
                for (i in points.indices.reversed()) {
                    val p = points[i]
                    if (i == points.lastIndex) {
                        path.lineTo(p.x, centerY + p.y)
                    } else {
                        val pNext = points[i + 1]
                        val midX = (pNext.x + p.x) / 2f
                        path.cubicTo(midX, centerY + pNext.y, midX, centerY + p.y, p.x, centerY + p.y)
                    }
                }
                path.close()

                // Draw filled gradient body
                drawPath(
                    path = path,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            animatedColor.copy(alpha = 0.1f * activeAlpha),
                            animatedColor.copy(alpha = 0.8f * activeAlpha),
                            animatedColor.copy(alpha = 0.1f * activeAlpha)
                        ),
                        startY = centerY - maxHeightPx / 2,
                        endY = centerY + maxHeightPx / 2
                    )
                )

                // Draw sleek solid rim
                drawPath(
                    path = path,
                    color = animatedColor.copy(alpha = activeAlpha),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 2.dp.toPx(),
                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                    )
                )
            } else if (points.size == 1) {
                val p = points.first()
                drawCircle(
                    color = animatedColor.copy(alpha = activeAlpha),
                    radius = p.y,
                    center = Offset(p.x, centerY)
                )
            }
        }
    }
}
