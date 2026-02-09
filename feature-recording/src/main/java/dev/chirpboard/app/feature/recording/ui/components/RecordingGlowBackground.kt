package dev.chirpboard.app.feature.recording.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Background glow effect for the recording screen.
 * 
 * Displays a subtle radial gradient glow that changes based on recording state:
 * - Recording: Red glow (error color)
 * - Paused: Amber glow (tertiary color)
 * - Idle: No glow
 * 
 * Uses a single animated glow to prevent dual-glow artifacts during transitions.
 * Brush and colors are cached to avoid per-frame allocations on 120Hz displays.
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
    val errorColor = MaterialTheme.colorScheme.error
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    
    // Single animated color that transitions between states (no dual-glow conflict)
    val targetGlowColor = when {
        isRecording -> errorColor
        isPaused -> tertiaryColor
        else -> Color.Transparent
    }
    
    val glowColor by animateColorAsState(
        targetValue = targetGlowColor,
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "glowColor"
    )
    
    // Single animated alpha for fade in/out
    val glowAlpha by animateFloatAsState(
        targetValue = if (isRecording || isPaused) 1f else 0f,
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "glowAlpha"
    )
    
    // Cache gradient colors to avoid per-frame allocations
    val primaryGlowColor = remember(glowColor) {
        glowColor.copy(alpha = 0.15f)
    }
    val secondaryGlowColor = remember(glowColor) {
        glowColor.copy(alpha = 0.05f)
    }
    
    // Cache the brush - only recreates when glowColor changes
    val glowBrush = remember(primaryGlowColor, secondaryGlowColor) {
        Brush.radialGradient(
            colors = listOf(
                primaryGlowColor,
                secondaryGlowColor,
                Color.Transparent,
            ),
            radius = 800f,
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Single glow box with GPU-accelerated alpha animation
        if (glowAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(alpha = glowAlpha)  // GPU-accelerated alpha
                    .background(glowBrush)
            )
        }
    }
}
