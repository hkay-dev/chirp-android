package dev.chirpboard.app.feature.keyboard.ui

/** Delay after the initial delete before character repeat begins. */
const val BackspaceInitialRepeatDelayMs = 400L

/** Hold duration before backspace switches from characters to whole words. */
const val BackspaceWordModeHoldMs = 1_500L

fun shouldEnterBackspaceWordMode(holdDurationMs: Long): Boolean = holdDurationMs >= BackspaceWordModeHoldMs

/**
 * Returns the delay before the next repeat delete.
 *
 * Character mode eases from ~120ms down to ~35ms over the first two seconds of holding.
 * Word mode stays fast but slightly slower than peak character speed for readability.
 */
fun backspaceRepeatIntervalMs(
    holdDurationMs: Long,
    wordMode: Boolean,
): Long {
    if (wordMode) {
        val wordPhaseMs = (holdDurationMs - BackspaceWordModeHoldMs).coerceAtLeast(0L)
        val rampMs = 1_500L
        val t = (wordPhaseMs.toFloat() / rampMs).coerceIn(0f, 1f)
        val eased = t * t
        return (110 - (40 * eased)).toLong().coerceAtLeast(70L)
    }

    val repeatPhaseMs = (holdDurationMs - BackspaceInitialRepeatDelayMs).coerceAtLeast(0L)
    val rampMs = 2_000L
    val t = (repeatPhaseMs.toFloat() / rampMs).coerceIn(0f, 1f)
    val eased = t * t
    return (120 - (85 * eased)).toLong().coerceAtLeast(35L)
}
