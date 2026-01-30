package dev.parakeeboard.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.parakeeboard.app.ui.theme.ParakeetTheme

/**
 * Displays animated vertical bars representing audio amplitude.
 * Used during recording state to provide visual feedback of audio input.
 *
 * @param amplitudes List of normalized amplitude values (0-1). Recent samples that will
 *                   be mapped to bars. If fewer values than barCount, remaining bars use 0.
 *                   If more values, only the last barCount values are used.
 * @param modifier Modifier for the composable
 * @param barCount Number of vertical bars to display
 * @param barColor Color of the bars
 * @param barWidth Width of each individual bar
 * @param barSpacing Horizontal spacing between bars
 */
@Composable
fun WaveformVisualizer(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier,
    barCount: Int = 5,
    barColor: Color = MaterialTheme.colorScheme.primary,
    barWidth: Dp = 8.dp,
    barSpacing: Dp = 4.dp
) {
    // Take the last barCount amplitudes, or pad with zeros if not enough
    val displayAmplitudes = when {
        amplitudes.size >= barCount -> amplitudes.takeLast(barCount)
        else -> List(barCount - amplitudes.size) { 0f } + amplitudes
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(barSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        displayAmplitudes.forEachIndexed { index, amplitude ->
            WaveformBar(
                amplitude = amplitude.coerceIn(0f, 1f),
                color = barColor,
                barWidth = barWidth,
                modifier = Modifier.fillMaxHeight()
            )
        }
    }
}

/**
 * A single animated bar in the waveform visualizer.
 *
 * @param amplitude Normalized amplitude value (0-1)
 * @param color Color of the bar
 * @param barWidth Width of the bar
 * @param modifier Modifier for the bar
 */
@Composable
private fun WaveformBar(
    amplitude: Float,
    color: Color,
    barWidth: Dp,
    modifier: Modifier = Modifier
) {
    // Minimum height of 20% so bars are always visible
    val minHeightFraction = 0.2f
    val targetHeightFraction = minHeightFraction + (amplitude * (1f - minHeightFraction))

    val animatedHeightFraction by animateFloatAsState(
        targetValue = targetHeightFraction,
        animationSpec = tween(durationMillis = 100),
        label = "barHeight"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(barWidth)
                .fillMaxHeight(animatedHeightFraction)
                .clip(RoundedCornerShape(barWidth / 2))
                .background(color)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF2B2930)
@Composable
private fun WaveformVisualizerPreview() {
    ParakeetTheme {
        WaveformVisualizer(
            amplitudes = listOf(0.3f, 0.7f, 0.5f, 0.9f, 0.4f),
            modifier = Modifier.height(60.dp)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF2B2930)
@Composable
private fun WaveformVisualizerEmptyPreview() {
    ParakeetTheme {
        WaveformVisualizer(
            amplitudes = emptyList(),
            modifier = Modifier.height(60.dp)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF2B2930)
@Composable
private fun WaveformVisualizerPartialPreview() {
    ParakeetTheme {
        WaveformVisualizer(
            amplitudes = listOf(0.5f, 0.8f),
            modifier = Modifier.height(60.dp),
            barCount = 5
        )
    }
}
