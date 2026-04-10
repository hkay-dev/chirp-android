package dev.chirpboard.app.feature.recording.ui.components

import androidx.compose.ui.platform.LocalDensity
import kotlin.math.pow
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
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
    waveformBuffer: dev.chirpboard.app.core.recording.WaveformBuffer,
    sampleCount: Long,
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
        targetValue = if (isActive && waveformBuffer.count > 0) 1f else 0f,
        animationSpec = tween(300, easing = EaseInOut),
        label = "activeAlpha",
    )

    val localDensity = LocalDensity.current
    val barWidthPx = remember(localDensity) { with(localDensity) { 5.dp.toPx() } }
    val barSpacingPx = remember(localDensity) { with(localDensity) { 5.dp.toPx() } }
    val stepPx = barWidthPx + barSpacingPx

    val scrollSpeed = 0.15f
    val smoothSampleCount by animateFloatAsState(
        targetValue = sampleCount.toFloat() * scrollSpeed,
        animationSpec = if (sampleCount == 0L) tween(0) else spring(stiffness = Spring.StiffnessLow),
        label = "smoothSampleCount"
    )

    val newestAmp = waveformBuffer.lastOrNull() ?: 0f
    val animatedNewestAmp by animateFloatAsState(
        targetValue = newestAmp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "newestAmp",
    )

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

        val minHeightPx = minBarHeight.toPx()
        val maxHeightPx = maxBarHeight.toPx()

        // Draw dotted line when not active (fades out when waveform appears)
        if (activeAlpha < 1f) {
            val dottedAlpha = (1f - activeAlpha) * 0.3f
            drawLine(
                color = animatedColor.copy(alpha = dottedAlpha),
                start = Offset(5.dp.toPx(), centerY),
                end = Offset(canvasWidth - 5.dp.toPx(), centerY),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = dashEffect,
            )
        }

        // Draw waveform bars with animated offset
        if (activeAlpha > 0f) {
            val n = waveformBuffer.count
            val firstSampleIndex = sampleCount - n

            for (i in 0 until n) {
                val absoluteIdx = firstSampleIndex + i
                // Let the camera slide slowly
                val scrollSpeed = 0.15f
                val virtualCameraX = smoothSampleCount * scrollSpeed

                // The index of the bar, mapped to the slow camera space
                val barVirtualX = absoluteIdx.toFloat() * scrollSpeed

                // How far is this bar from the camera?
                // We anchor the camera's front edge to the right side of the screen.
                val distanceInSlots = virtualCameraX - barVirtualX - scrollSpeed

                val xCenter = canvasWidth - (distanceInSlots * stepPx)

                // Culling: Only draw bars that are visible on screen
                if (xCenter < -barWidthPx / 2f || xCenter > canvasWidth + barWidthPx / 2f) {
                    continue
                }

                val amp = if (i == n - 1) animatedNewestAmp else waveformBuffer.get(i)
                val scaledAmplitude = amp * activeAlpha
                val boostedAmplitude = (scaledAmplitude.pow(0.7f) * 1.5f)
                val barHeight = (minHeightPx + (boostedAmplitude * (maxHeightPx - minHeightPx)))
                    .coerceIn(minHeightPx, maxHeightPx)

                val halfHeight = barHeight / 2f
                drawLine(
                    color = animatedColor.copy(alpha = 0.8f * activeAlpha),
                    start = Offset(xCenter, centerY - halfHeight),
                    end = Offset(xCenter, centerY + halfHeight),
                    strokeWidth = barWidthPx,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
