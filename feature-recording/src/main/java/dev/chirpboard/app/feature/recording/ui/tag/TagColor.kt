package dev.chirpboard.app.feature.recording.ui.tag

import androidx.compose.ui.graphics.Color

/**
 * Parse a stored tag hex color to a Compose Color, falling back on malformed input.
 * Call sites in list items should wrap this in `remember` — parsing is not free and
 * list rows recompose frequently during scroll.
 */
internal fun parseTagColor(
    hexColor: String,
    fallbackColor: Color,
): Color =
    try {
        Color(android.graphics.Color.parseColor(hexColor))
    } catch (_: IllegalArgumentException) {
        fallbackColor
    }
