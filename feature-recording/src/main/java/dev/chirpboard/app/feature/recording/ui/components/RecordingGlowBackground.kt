package dev.chirpboard.app.feature.recording.ui.components

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Background glow effect for the recording screen.
 * 
 * Displays a subtle radial gradient glow that changes based on recording state:
 * - Recording: Red glow (error color)
 * - Paused: Amber glow (tertiary color)
 * - Idle: No glow
 * 
 * @param isRecording Whether actively recording
 * @param isPaused Whether recording is paused
 * @param modifier Optional modifier
 */
@Composable
fun RecordingGlowBackground(
    isRecording: Boolean,
    isPaused: Boolean,
    modifier: Modifier = Modifier
) {
    // Animated glow alpha for recording state (red)
    val recordingGlowAlpha by animateFloatAsState(
        targetValue = if (isRecording) 1f else 0f,
        animationSpec = tween(600, easing = EaseInOut),
        label = "recordingGlowAlpha",
    )

    // Animated glow alpha for paused state (amber)
    val pausedGlowAlpha by animateFloatAsState(
        targetValue = if (isPaused) 1f else 0f,
        animationSpec = tween(600, easing = EaseInOut),
        label = "pausedGlowAlpha",
    )

    val errorColor = MaterialTheme.colorScheme.error
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    Box(modifier = modifier.fillMaxSize()) {
        // Recording glow (red)
        if (recordingGlowAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                errorColor.copy(alpha = 0.15f * recordingGlowAlpha),
                                errorColor.copy(alpha = 0.05f * recordingGlowAlpha),
                                Color.Transparent,
                            ),
                            radius = 800f,
                        ),
                    ),
            )
        }

        // Paused glow (tertiary/amber)
        if (pausedGlowAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                tertiaryColor.copy(alpha = 0.12f * pausedGlowAlpha),
                                tertiaryColor.copy(alpha = 0.04f * pausedGlowAlpha),
                                Color.Transparent,
                            ),
                            radius = 800f,
                        ),
                    ),
            )
        }
    }
}
