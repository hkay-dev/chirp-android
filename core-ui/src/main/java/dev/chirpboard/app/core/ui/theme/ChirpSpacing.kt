package dev.chirpboard.app.core.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 4dp-based spacing scale (VIS-4).
 *
 * The app previously scattered raw 8/12/16/24/32.dp literals, producing the wandering home margins
 * the consistency findings call out. Reference these tokens instead of literals so the rhythm is
 * enforceable in one place.
 *
 * [ScreenHorizontal] is the canonical content margin for top-level screens (16.dp); use it for the
 * home stat row, list rows and dividers so left edges line up.
 */
object ChirpSpacing {
    /** 4.dp — hairline gaps, icon-to-label spacing. */
    val ExtraSmall: Dp = 4.dp

    /** 8.dp — tight intra-component spacing. */
    val Small: Dp = 8.dp

    /** 12.dp — default gap between related elements. */
    val Medium: Dp = 12.dp

    /** 16.dp — standard content spacing; equals [ScreenHorizontal]. */
    val Large: Dp = 16.dp

    /** 24.dp — section separation, generous insets. */
    val ExtraLarge: Dp = 24.dp

    /** 32.dp — large vertical rhythm, empty-state padding. */
    val ExtraExtraLarge: Dp = 32.dp

    /** Canonical horizontal content margin for top-level screens. */
    val ScreenHorizontal: Dp = Large
}
