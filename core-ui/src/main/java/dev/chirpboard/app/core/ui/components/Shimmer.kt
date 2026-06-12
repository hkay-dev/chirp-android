package dev.chirpboard.app.core.ui.components

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.theme.ChirpShapes

/** Default sweep period for [shimmer]. */
private const val ShimmerPeriodMs = 1100

/** Default breathing period for [brandedPulse]. */
private const val PulsePeriodMs = 1200

/**
 * Compute the horizontal translation of the shimmer gradient for a given progress.
 *
 * The gradient is [bandWidth] wide and sweeps from fully off the left edge to fully off the right
 * edge of a [contentWidth]-wide surface as [progress] goes 0f → 1f. Extracted as a pure function so
 * the sweep geometry is unit-testable without a Compose runtime (LOAD-4).
 *
 * @return the x-offset (in px) of the gradient's left edge.
 */
internal fun shimmerTranslate(
    progress: Float,
    contentWidth: Float,
    bandWidth: Float,
): Float {
    val start = -bandWidth
    val end = contentWidth + bandWidth
    return start + (end - start) * progress.coerceIn(0f, 1f)
}

/**
 * True when the system "remove animations" / reduced-motion setting is active.
 *
 * Reads `Settings.Global.ANIMATOR_DURATION_SCALE`; a scale of 0 means the user has disabled
 * animations system-wide, so motion-heavy affordances should fall back to a static state.
 */
@Composable
internal fun reducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale =
            runCatching {
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                )
            }.getOrDefault(1f)
        scale == 0f
    }
}

/**
 * Animated shimmer sweep, for masking loads (LOAD-4).
 *
 * Draws a translucent highlight band that sweeps left→right across the composable, on top of its
 * existing content/background. Apply to a [SkeletonPlaceholder] or any surface you want to read as
 * "loading". When reduced-motion is enabled the sweep is omitted (a static, faintly-highlighted
 * surface is shown instead), keeping the UI calm and accessible.
 *
 * @param highlightColor the moving highlight; defaults to a translucent on-surface tint.
 */
fun Modifier.shimmer(
    highlightColor: Color? = null,
): Modifier =
    composed {
        val baseHighlight = highlightColor ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)
        if (reducedMotionEnabled()) {
            // Static, low-key highlight so the surface still reads as a placeholder.
            return@composed this.drawWithCache {
                val brush =
                    Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, baseHighlight.copy(alpha = 0.10f), Color.Transparent),
                    )
                onDrawWithContent {
                    drawContent()
                    drawRect(brush = brush)
                }
            }
        }

        val transition = rememberInfiniteTransition(label = "shimmer")
        // Keep progress as State and read it inside drawWithCache so the sweep only invalidates the
        // draw phase, never recomposing the host (matches the StatsPillRow pulse pattern).
        val progress =
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = ShimmerPeriodMs, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                label = "shimmerProgress",
            )

        this.drawWithCache {
            val bandWidth = size.width * 0.45f
            val colors = listOf(Color.Transparent, baseHighlight, Color.Transparent)
            onDrawWithContent {
                drawContent()
                val translate = shimmerTranslate(progress.value, size.width, bandWidth)
                drawRect(
                    brush =
                        Brush.horizontalGradient(
                            colors = colors,
                            startX = translate,
                            endX = translate + bandWidth,
                        ),
                )
            }
        }
    }

/**
 * A shimmering placeholder block, sized for skeleton screens (LOAD-4).
 *
 * Renders a rounded surfaceVariant rectangle with a [shimmer] sweep — use it for title bars, list
 * rows and pill placeholders while real content loads (e.g. home first-load rows, the studio title
 * skeleton). Compose several to assemble a skeleton layout.
 *
 * @param width null fills the available width; otherwise a fixed width.
 */
@Composable
fun SkeletonPlaceholder(
    modifier: Modifier = Modifier,
    width: Dp? = null,
    height: Dp = 16.dp,
    shape: androidx.compose.ui.graphics.Shape = ChirpShapes.Small,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val sized =
        if (width != null) modifier.width(width).height(height) else modifier.height(height)
    Box(
        modifier =
            sized
                .background(color = color, shape = shape)
                .shimmer(),
    )
}

/**
 * Branded breathing pulse, for masking loads with no measurable progress (LOAD-4).
 *
 * Gently animates alpha between [minAlpha] and 1f, so an affordance (e.g. the keyboard "warming"
 * mic, the recognition dialog mic) reads as alive rather than frozen. Prefer this over an
 * indeterminate progress bar when there is no real progress signal. Respects reduced-motion by
 * holding a steady, slightly-dimmed alpha.
 */
fun Modifier.brandedPulse(
    minAlpha: Float = 0.55f,
    periodMs: Int = PulsePeriodMs,
): Modifier =
    composed {
        if (reducedMotionEnabled()) {
            return@composed this.graphicsLayer { alpha = minAlpha }
        }
        val transition = rememberInfiniteTransition(label = "brandedPulse")
        val pulse =
            transition.animateFloat(
                initialValue = minAlpha,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = periodMs, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "brandedPulseAlpha",
            )
        this.graphicsLayer { alpha = pulse.value }
    }
