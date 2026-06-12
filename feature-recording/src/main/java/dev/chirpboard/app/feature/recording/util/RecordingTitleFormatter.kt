package dev.chirpboard.app.feature.recording.util

import android.content.Context
import android.text.format.DateUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of default recording titles ("Jun 12, 3:42 PM" / "Jun 12, 15:42").
 *
 * Uses [DateUtils.formatDateTime] so the title honors the device's 12/24-hour setting
 * and locale instead of the previous hardcoded `Locale.US` 12-hour pattern, and keeps
 * the start/stop/recovery call sites from drifting apart.
 */
@Singleton
class RecordingTitleFormatter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun format(epochMs: Long): String =
            DateUtils.formatDateTime(
                context,
                epochMs,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME,
            )
    }
