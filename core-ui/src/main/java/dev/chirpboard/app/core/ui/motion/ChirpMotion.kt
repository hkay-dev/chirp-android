package dev.chirpboard.app.core.ui.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize

object ChirpMotion {
    const val NAV_TRANSITION_MS = 450
    const val NAV_FADE_MS = 400
    const val NAV_SLIDE_OFFSET_DIVISOR = 12

    const val STUDIO_REVEAL_MS = 420
    const val STUDIO_HIDE_MS = 260
    const val RECORD_HANDOFF_MS = 480L

    const val TIMER_TICK_MS = 100L
    const val PLAYBACK_TICK_MS = 500L

    val layoutMotionSpring =
        spring<Dp>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        )

    val layoutSizeSpring =
        spring<IntSize>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        )

    val studioAlphaTween =
        tween<Float>(
            durationMillis = STUDIO_REVEAL_MS,
            easing = FastOutSlowInEasing,
        )

    val studioRevealTransition =
        fadeIn(tween(durationMillis = STUDIO_REVEAL_MS, easing = FastOutSlowInEasing)) +
            slideInVertically(
                animationSpec = tween(durationMillis = STUDIO_REVEAL_MS, easing = FastOutSlowInEasing),
                initialOffsetY = { fullHeight -> fullHeight / 20 },
            )

    val studioHideTransition =
        fadeOut(tween(durationMillis = STUDIO_HIDE_MS, easing = FastOutSlowInEasing)) +
            slideOutVertically(
                animationSpec = tween(durationMillis = STUDIO_HIDE_MS, easing = FastOutSlowInEasing),
                targetOffsetY = { fullHeight -> fullHeight / 24 },
            )

    val studioContentCrossfade: ContentTransform =
        fadeIn(tween(durationMillis = STUDIO_REVEAL_MS, easing = FastOutSlowInEasing)) togetherWith
            fadeOut(tween(durationMillis = STUDIO_HIDE_MS, easing = FastOutSlowInEasing))

    /**
     * Crossfade for keyboard IME processing phases (transcribing ↔ polishing).
     * Slightly shorter fade-out than fade-in so status text feels responsive without jarring cuts.
     */
    val keyboardProcessingCrossfade: ContentTransform =
        fadeIn(tween(durationMillis = 250, easing = FastOutSlowInEasing)) togetherWith
            fadeOut(tween(durationMillis = 200, easing = FastOutSlowInEasing))
}
