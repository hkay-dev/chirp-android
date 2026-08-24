package dev.chirpboard.app.core.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * AlertDialog replacement with smooth scale + fade entrance animation.
 *
 * Uses the same API as Material 3 [AlertDialog] but adds:
 * - Scale from 0.9 -> 1.0 on enter (250ms)
 * - Fade from 0.0 -> 1.0 on enter (250ms)
 *
 * Drop-in replacement: swap `AlertDialog(` for `AnimatedAlertDialog(`.
 */
@Composable
fun AnimatedAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
) {
    var animateIn by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        animateIn = true
    }

    // Held as State (not read here) so the entrance drives only the graphics layer — reading the
    // values in the body would recompose the whole dialog on every frame of the 250ms enter.
    val scale = animateFloatAsState(
        targetValue = if (animateIn) 1f else 0.9f,
        animationSpec = tween(
            durationMillis = 250,
            easing = FastOutSlowInEasing
        ),
        label = "dialog_scale"
    )

    val alpha = animateFloatAsState(
        targetValue = if (animateIn) 1f else 0f,
        animationSpec = tween(
            durationMillis = 250,
            easing = FastOutSlowInEasing
        ),
        label = "dialog_alpha"
    )

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier
            .graphicsLayer {
                val current = scale.value
                scaleX = current
                scaleY = current
                this.alpha = alpha.value
            },
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
    )
}
