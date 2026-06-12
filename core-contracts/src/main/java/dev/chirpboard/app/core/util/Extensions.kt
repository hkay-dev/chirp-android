package dev.chirpboard.app.core.util

import java.text.SimpleDateFormat
import java.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val nowCalendar = object : ThreadLocal<Calendar>() {
    override fun initialValue() = Calendar.getInstance()
}
private val thenCalendar = object : ThreadLocal<Calendar>() {
    override fun initialValue() = Calendar.getInstance()
}
private val yesterdayCalendar = object : ThreadLocal<Calendar>() {
    override fun initialValue() = Calendar.getInstance()
}
private val monthDayFormat = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("MMM d", Locale.getDefault())
}
private val monthDayYearFormat = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
}

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
 *
 * I18N-08: UI callers pass [today]/[yesterday] from string resources
 * (core-contracts `date_today`/`date_yesterday`); the defaults exist for
 * non-UI callers and keep this a pure function.
 */
fun Date.formatRelative(
    today: String = "Today",
    yesterday: String = "Yesterday",
): String {
    val now = nowCalendar.get()!!.apply { timeInMillis = System.currentTimeMillis() }
    val then = thenCalendar.get()!!.apply { time = this@formatRelative }

    return when {
        isSameDay(now, then) -> today
        isYesterday(now, then) -> yesterday
        isSameYear(now, then) -> monthDayFormat.get()!!.format(this)
        else -> monthDayYearFormat.get()!!.format(this)
    }
}

private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
           cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}

private fun isYesterday(now: Calendar, then: Calendar): Boolean {
    val yesterday = yesterdayCalendar.get()!!.apply {
        timeInMillis = now.timeInMillis
        add(Calendar.DAY_OF_YEAR, -1)
    }
    return isSameDay(yesterday, then)
}

private fun isSameYear(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
}
