package dev.chirpboard.app.core.ui.components.recording

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.chirpboard.app.core.ui.components.reducedMotionEnabled
import dev.chirpboard.app.core.ui.theme.chirpAccents

private const val GLOW_TWEEN_MS = 1200
private const val GLOW_MID_ALPHA = 0.15f
private const val GLOW_PEAK_ALPHA = 0.35f
private const val GLOW_FLOOR_ALPHA = 0.04f

/** Fixed breathing-phase used when reduced motion is on: a steady mid-intensity glow. */
private const val STATIC_GLOW_PROGRESS = 0.6f

/**
 * Breathe a glow-band alpha between a near-transparent [floorAlpha] (at rest) and [peakAlpha] as
 * [progress] goes 0f -> 1f. Extracted as a pure function so the breathing math is unit-testable
 * without a Compose runtime; mirrors the prior errorContainer->error fade with a single hue.
 */
internal fun glowAlpha(progress: Float, floorAlpha: Float, peakAlpha: Float): Float =
    floorAlpha + (peakAlpha - floorAlpha) * progress.coerceIn(0f, 1f)

/**
 * Pulsing vertical "we are recording" glow drawn behind live-capture content.
 *
 * [color] defaults to the cohesive brand "recording/live" accent
 * ([ChirpAccents.recordingLive][dev.chirpboard.app.core.ui.theme.ChirpAccents.recordingLive]) so
 * the glow is on-brand everywhere instead of the off-brand Material error red it previously used
 * (PRM-7). The single accent breathes between a near-transparent floor and its peak alpha, so it
 * reads as a soft live pulse rather than an error wash.
 */
@Composable
fun RecordingGlowBackground(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.chirpAccents.recordingLive,
) {
    // Reduced-motion: hold a steady mid-intensity glow — the "recording live" signal stays
    // visible without the breathing pulse (matches shimmer/brandedPulse fallbacks).
    val reduceMotion = reducedMotionEnabled()
    val glowProgress =
        if (reduceMotion) {
            null
        } else {
            // Animate a single Float and read it inside the draw lambda so the infinite transition
            // invalidates only the draw phase, never composition (no per-vsync recompose).
            rememberInfiniteTransition(label = "glowTransition").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(GLOW_TWEEN_MS, easing = EaseInOut),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "glowProgress",
            )
        }

    Spacer(
        modifier =
            modifier.drawWithCache {
                // The ramp is built once per size (and per colour); breathing is applied at draw
                // time as a layer alpha, so no gradient/shader is allocated per vsync. The stop
                // alphas are relative to the peak, which keeps the peak of the breath identical to
                // the per-frame form it replaces.
                val brush =
                    Brush.verticalGradient(
                        colors =
                            listOf(
                                Color.Transparent,
                                color.copy(alpha = GLOW_MID_ALPHA / GLOW_PEAK_ALPHA),
                                color.copy(alpha = 1f),
                            ),
                        startY = 0f,
                        endY = size.height,
                    )
                onDrawBehind {
                    // Breathe the alpha (not the hue) of the single recording accent: a
                    // near-transparent floor at rest up to the peak.
                    val progress = glowProgress?.value ?: STATIC_GLOW_PROGRESS
                    drawRect(
                        brush = brush,
                        alpha = glowAlpha(progress, GLOW_FLOOR_ALPHA, GLOW_PEAK_ALPHA),
                    )
                }
            },
    )
}
