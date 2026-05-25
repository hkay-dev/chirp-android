package dev.chirpboard.app.core.ui.animation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Standardized animation constants for consistent UI motion across the app.
 * 
 * Designed for 120Hz displays where each frame must render in 8.3ms.
 * Using standardized durations ensures visual consistency and predictable performance.
 */
object AnimationConstants {
    
    // ==================== Duration Tiers ====================
    
    /** Quick animations: micro-interactions, ripples, icon swaps */
    const val DURATION_QUICK_MS = 200
    
    /** Standard animations: state transitions, visibility changes */
    const val DURATION_STANDARD_MS = 300
    
    /** Emphasis animations: important state changes (recording start/stop) */
    const val DURATION_EMPHASIS_MS = 400
    
    /** Glow/background transitions: slower for ambient effects */
    const val DURATION_GLOW_MS = 500
    
    // ==================== Easing Functions ====================
    
    /** Standard easing for most animations - natural deceleration */
    val EASING_STANDARD = FastOutSlowInEasing
    
    // ==================== Pre-built Animation Specs ====================
    
    /** Quick tween for micro-interactions */
    fun <T> tweenQuick() = tween<T>(
        durationMillis = DURATION_QUICK_MS,
        easing = EASING_STANDARD
    )
    
    /** Standard tween for state transitions */
    fun <T> tweenStandard() = tween<T>(
        durationMillis = DURATION_STANDARD_MS,
        easing = EASING_STANDARD
    )
    
    /** Emphasis tween for important state changes */
    fun <T> tweenEmphasis() = tween<T>(
        durationMillis = DURATION_EMPHASIS_MS,
        easing = EASING_STANDARD
    )
    
    /** Glow tween for ambient background effects */
    fun <T> tweenGlow() = tween<T>(
        durationMillis = DURATION_GLOW_MS,
        easing = EASING_STANDARD
    )
    
    // ==================== Spring Specs ====================
    
    /** Bouncy spring for playful interactions (waveform bars, button feedback) */
    fun <T> springBouncy() = spring<T>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )
    
    /** Snappy spring for quick responsive feedback */
    fun <T> springSnappy() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh
    )
    
    /** Gentle spring for smooth settling (sliders, progress) */
    fun <T> springGentle() = spring<T>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow
    )
    
    // ==================== Timer/Update Intervals ====================
    
    /** Timer display update interval (ms) - human readable, not too frequent */
    const val TIMER_UPDATE_INTERVAL_MS = 500L
    
    /** Amplitude debounce interval (ms) - ~60fps, sufficient for visual smoothness */
    const val AMPLITUDE_DEBOUNCE_MS = 16L
    
    /** Slider position animation duration (ms) */
    const val SLIDER_ANIMATION_MS = 100
    
    // ==================== Scale Values ====================
    
    /** Scale factor for press feedback */
    const val PRESS_SCALE = 0.98f
    
    /** Scale factor for dialog entry animation */
    const val DIALOG_ENTRY_SCALE = 0.85f
}
