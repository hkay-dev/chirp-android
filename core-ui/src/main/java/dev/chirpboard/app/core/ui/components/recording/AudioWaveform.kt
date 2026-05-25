package dev.chirpboard.app.core.ui.components.recording

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.recording.WaveformBuffer
import kotlin.math.pow

/**
 * Audio waveform visualization component with smooth 60fps animation.
 *
 * Displays real-time audio amplitude as vertical bars when recording,
 * or a subtle dotted line when idle or paused.
 */
@Composable
fun AudioWaveform(
    waveformBuffer: WaveformBuffer,
    sampleCount: Long,
    isActive: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    barCount: Int = 42,
    minBarHeight: Dp = 4.dp,
    maxBarHeight: Dp = 120.dp,
 ) {
    val animatedColor = animateColorAsState(
        targetValue = color,
        animationSpec = tween(400, easing = EaseInOut),
        label = "waveformColor",
    )
    val activeAlpha = animateFloatAsState(
        targetValue = if (isActive && sampleCount > 0L) 1f else 0f,
        animationSpec = tween(300, easing = EaseInOut),
        label = "activeAlpha",
    )


    val smoothSampleCount = animateFloatAsState(
        targetValue = sampleCount.toFloat(),
        animationSpec = if (sampleCount == 0L) tween(0) else spring(stiffness = Spring.StiffnessLow),
        label = "smoothSampleCount",
    )
    val newestAmp = waveformBuffer.lastOrNull() ?: 0f
    val animatedNewestAmp = animateFloatAsState(
        targetValue = newestAmp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "newestAmp",
    )


    Spacer(
        modifier =
            modifier
                .fillMaxWidth()
                .height(maxBarHeight + 16.dp)
                .graphicsLayer()
                .drawWithCache {
                    val minHeightPx = minBarHeight.toPx()
                    val maxHeightPx = maxBarHeight.toPx()
                    val barWidthPx = 5.dp.toPx()
                    val barSpacingPx = 5.dp.toPx()
                    val stepPx = barWidthPx + barSpacingPx
                    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 8.dp.toPx()), 0f)
                    
                    onDrawBehind {
                        val canvasWidth = size.width
                        val centerY = size.height / 2f
                        val colorValue = animatedColor.value
                        val activeAlphaValue = activeAlpha.value
                        val smoothSampleCountValue = smoothSampleCount.value
                        val newestAmpValue = animatedNewestAmp.value

                        if (activeAlphaValue < 1f) {
                            val dottedAlpha = (1f - activeAlphaValue) * 0.3f
                            drawLine(
                                color = colorValue.copy(alpha = dottedAlpha),
                                start = Offset(5.dp.toPx(), centerY),
                                end = Offset(canvasWidth - 5.dp.toPx(), centerY),
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                                pathEffect = dashEffect,
                            )
                        }

                        if (activeAlphaValue <= 0f) return@onDrawBehind

                        val totalSamples = waveformBuffer.count
                        if (totalSamples == 0) return@onDrawBehind

                        val firstSampleIndex = sampleCount - totalSamples
                        val visibleSampleCount = barCount.coerceAtLeast(1) + 2
                        val startIndex = (totalSamples - visibleSampleCount).coerceAtLeast(0)

                        for (i in startIndex until totalSamples) {
                            val absoluteIdx = firstSampleIndex + i
                            val distanceInSlots = smoothSampleCountValue - absoluteIdx.toFloat() - 1f
                            val xCenter = canvasWidth - (distanceInSlots * stepPx)

                            if (xCenter < -barWidthPx / 2f || xCenter > canvasWidth + barWidthPx / 2f) {
                                continue
                            }

                            val amp = if (i == totalSamples - 1) newestAmpValue else waveformBuffer.get(i)
                            val scaledAmplitude = amp * activeAlphaValue
                            val boostedAmplitude = (scaledAmplitude.pow(0.7f) * 1.5f)
                            val barHeight =
                                (minHeightPx + (boostedAmplitude * (maxHeightPx - minHeightPx)))
                                    .coerceIn(minHeightPx, maxHeightPx)
                            val halfHeight = barHeight / 2f

                            drawLine(
                                color = colorValue.copy(alpha = 0.8f * activeAlphaValue),
                                start = Offset(xCenter, centerY - halfHeight),
                                end = Offset(xCenter, centerY + halfHeight),
                                strokeWidth = barWidthPx,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
    )
}