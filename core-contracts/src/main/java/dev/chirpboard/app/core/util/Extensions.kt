package dev.chirpboard.app.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Format duration as "MM:SS" or "HH:MM:SS" for longer durations.
 *
 * Locale.US, not the default: these are digit-only timers rendered next to the app's
 * own numerals, and the default locale would switch them to Arabic-Indic digits on
 * their own while the surrounding UI kept Latin ones.
 */
fun Duration.formatDuration(): String {
    val totalSeconds = inWholeSeconds
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
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
    // Zone and locale are read per call. The previous ThreadLocal Calendar/SimpleDateFormat
    // pair captured both at first use, so flying across a timezone or changing the device
    // language mislabelled the date on every recording card until the process restarted.
    val formatters = localizedFormatters()
    val thenDate = Instant.ofEpochMilli(time).atZone(formatters.zone).toLocalDate()
    val nowDate = LocalDate.now(formatters.zone)

    return when {
        thenDate == nowDate -> today
        thenDate == nowDate.minusDays(1) -> yesterday
        thenDate.year == nowDate.year -> formatters.monthDay.format(thenDate)
        else -> formatters.monthDayYear.format(thenDate)
    }
}

/** Pattern parsing is not free and this runs per recording card, so it is cached per zone/locale. */
private class LocalizedFormatters(val zone: ZoneId, val locale: Locale) {
    val monthDay: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", locale)
    val monthDayYear: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", locale)
}

@Volatile
private var cachedFormatters: LocalizedFormatters? = null

private fun localizedFormatters(): LocalizedFormatters {
    val zone = ZoneId.systemDefault()
    val locale = Locale.getDefault()
    val cached = cachedFormatters
    if (cached != null && cached.zone == zone && cached.locale == locale) {
        return cached
    }
    return LocalizedFormatters(zone, locale).also { cachedFormatters = it }
}
