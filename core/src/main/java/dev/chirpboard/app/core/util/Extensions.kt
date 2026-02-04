package dev.chirpboard.app.core.util

import java.text.SimpleDateFormat
import java.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Format duration as "MM:SS" or "HH:MM:SS" for longer durations.
 */
fun Duration.formatDuration(): String {
    val totalSeconds = inWholeSeconds
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

/**
 * Format milliseconds as duration string.
 */
fun Long.formatAsDuration(): String = this.milliseconds.formatDuration()

/**
 * Format date relative to now (Today, Yesterday, or date).
 */
fun Date.formatRelative(): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { time = this@formatRelative }
    
    return when {
        isSameDay(now, then) -> "Today"
        isYesterday(now, then) -> "Yesterday"
        isSameYear(now, then) -> SimpleDateFormat("MMM d", Locale.getDefault()).format(this)
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(this)
    }
}

/**
 * Format date with time.
 */
fun Date.formatDateTime(): String {
    return SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(this)
}

private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
           cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

private fun isYesterday(now: Calendar, then: Calendar): Boolean {
    val yesterday = Calendar.getInstance().apply {
        time = now.time
        add(Calendar.DAY_OF_YEAR, -1)
    }
    return isSameDay(yesterday, then)
}

private fun isSameYear(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
}

/**
 * Truncate string with ellipsis if longer than maxLength.
 */
fun String.truncate(maxLength: Int): String {
    return if (length <= maxLength) this else "${take(maxLength - 1)}..."
}

/**
 * Capitalize first letter of each word.
 */
fun String.titleCase(): String {
    return split(" ").joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
}
