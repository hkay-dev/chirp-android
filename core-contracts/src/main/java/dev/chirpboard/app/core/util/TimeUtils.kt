package dev.chirpboard.app.core.util

/**
 * Format seconds into a human-readable duration string.
 *
 * @param seconds Total seconds to format
 * @return Formatted string:
 *   - "MM:SS" for durations under 1 hour (e.g., "05:23")
 *   - "H:MM:SS" for durations 1 hour or more (e.g., "1:30:45")
 */
fun formatTime(seconds: Long): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60

    return if (hrs > 0) {
        String.format("%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format("%02d:%02d", mins, secs)
    }
}

/**
 * Format milliseconds into a human-readable duration string.
 *
 * @param milliseconds Total milliseconds to format
 * @return Formatted string (see [formatTime])
 */
fun formatTimeMs(milliseconds: Long): String = formatTime(milliseconds / 1000)
