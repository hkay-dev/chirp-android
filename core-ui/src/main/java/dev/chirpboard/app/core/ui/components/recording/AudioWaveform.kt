package dev.chirpboard.app.core.ui.components.recording

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.recording.WaveformBuffer
import dev.chirpboard.app.core.ui.theme.chirpAccents
import kotlin.math.ceil
import kotlin.math.pow

/**
 * Audio waveform visualization for live recording.
 *
 * Bar positions track [sampleCount] directly so scroll speed matches incoming samples.
 * Amplitudes are read from [waveformBuffer] without per-bar smoothing to avoid
 * height flicker when the newest bar becomes historical.
 *
 * Displays vertical bars when recording, or a subtle dotted line when idle or paused.
 *
 * Geometry (on-device sweep fix): the newest bar is anchored fully *inside* the right edge and
 * the canvas clips to its bounds, so bars can never bleed into the host card's padding; the
 * visible history window is derived from the actual canvas width ([barCount] only sets a floor),
 * so the bars always fill the width they are given instead of a fixed 42-slot viewport.
 *
 * [color] defaults to the cohesive brand "recording/live" accent
 * ([ChirpAccents.recordingLive][dev.chirpboard.app.core.ui.theme.ChirpAccents.recordingLive]) so
 * every consumer reads on-brand without re-specifying it (PRM-7); callers may still override it.
 */
@Composable
fun AudioWaveform(
    waveformBuffer: WaveformBuffer,
    sampleCount: Long,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.chirpAccents.recordingLive,
    barCount: Int = 42,
    minBarHeight: Dp = 4.dp,
    maxBarHeight: Dp = 120.dp,
    showIdlePlaceholder: Boolean = true,
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
    // Reused draw-phase scratch buffer: one snapshotInto per frame replaces a
    // per-bar synchronized get() that contended with the capture thread.
    val amplitudeSnapshot = remember(waveformBuffer) { FloatArray(waveformBuffer.capacity) }

    Spacer(
        modifier =
            modifier
                .fillMaxWidth()
                .height(maxBarHeight + 16.dp)
                // clipToBounds() == graphicsLayer(clip = true): keeps the standalone layer that
                // isolates amplitude redraws AND stops bars rendering outside the waveform's
                // bounds (they previously bled past the host card's inner padding).
                .clipToBounds()
                .drawBehind {
                    val minHeightPx = minBarHeight.toPx()
                    val maxHeightPx = maxBarHeight.toPx()
                    val barWidthPx = 5.dp.toPx()
                    val barSpacingPx = 5.dp.toPx()
                    val stepPx = barWidthPx + barSpacingPx

                    val canvasWidth = size.width
                    val centerY = size.height / 2f
                    val colorValue = animatedColor.value
                    val activeAlphaValue = activeAlpha.value
                    val scrollSampleCount = sampleCount.toFloat()

                    if (showIdlePlaceholder && activeAlphaValue < 1f) {
                        // Allocated only on placeholder frames; this lambda runs on every
                        // amplitude redraw while recording.
                        val dashEffect =
                            PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 8.dp.toPx()), 0f)
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

                    if (activeAlphaValue <= 0f) return@drawBehind

                    val totalSamples = waveformBuffer.snapshotInto(amplitudeSnapshot)
                    if (totalSamples == 0) return@drawBehind

                    val firstSampleIndex = sampleCount - totalSamples
                    val visibleSampleCount =
                        waveformVisibleSlotCount(
                            canvasWidthPx = canvasWidth,
                            stepPx = stepPx,
                            barCountFloor = barCount,
                        )
                    val startIndex = (totalSamples - visibleSampleCount).coerceAtLeast(0)
                    val newestBarCenterX = canvasWidth - barWidthPx / 2f

                    for (i in startIndex until totalSamples) {
                        val absoluteIdx = firstSampleIndex + i
                        val distanceInSlots = scrollSampleCount - absoluteIdx.toFloat() - 1f
                        val xCenter = newestBarCenterX - (distanceInSlots * stepPx)

                        if (xCenter < -barWidthPx / 2f) {
                            continue
                        }

                        val amp = amplitudeSnapshot[i]
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
                },
    )
}

/**
 * Number of history slots the draw loop walks: enough to span the actual canvas width (plus a
 * two-slot margin so a partially scrolled-out bar on the left edge still draws), with
 * [barCountFloor] as a lower bound for compatibility with callers sized around the historical
 * 42-bar default. Pure so the width-fill contract is unit-testable (on-device sweep fix).
 */
internal fun waveformVisibleSlotCount(
    canvasWidthPx: Float,
    stepPx: Float,
    barCountFloor: Int,
): Int {
    val slotsAcrossWidth = if (stepPx > 0f) ceil(canvasWidthPx / stepPx).toInt() else 0
    return maxOf(barCountFloor, slotsAcrossWidth).coerceAtLeast(1) + 2
}