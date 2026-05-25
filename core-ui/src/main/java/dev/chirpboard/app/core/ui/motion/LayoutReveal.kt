package dev.chirpboard.app.core.ui.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Reveals vertical chrome with expand-from-top + fade, pushing siblings when the parent uses
 * [animateContentSize] with [ChirpMotion.layoutSizeSpring].
 */
@Composable
fun PushDownReveal(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = ChirpMotion.pushDownRevealTransition,
        exit = ChirpMotion.pushDownHideTransition,
        label = "push_down_reveal",
        content = { content() },
    )
}

/** Modifier alias for columns that host [PushDownReveal] children. */
fun Modifier.animatePushDownLayout(): Modifier =
    animateContentSize(animationSpec = ChirpMotion.layoutSizeSpring)
