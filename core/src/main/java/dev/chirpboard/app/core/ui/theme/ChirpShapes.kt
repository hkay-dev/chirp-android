package dev.chirpboard.app.core.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Consistent shape scale aligned to Material 3 guidelines.
 * 
 * Use these instead of hardcoded RoundedCornerShape values to ensure
 * visual consistency across the entire app.
 */
object ChirpShapes {
    /** 4.dp — tiny accents, subtle rounding */
    val ExtraSmall: Shape = RoundedCornerShape(4.dp)

    /** 8.dp — small components, compact cards */
    val Small: Shape = RoundedCornerShape(8.dp)

    /** 12.dp — standard cards, containers */
    val Medium: Shape = RoundedCornerShape(12.dp)

    /** 16.dp — chips, tags, pill shapes */
    val Large: Shape = RoundedCornerShape(16.dp)

    /** 28.dp — large containers, prominent surfaces */
    val ExtraLarge: Shape = RoundedCornerShape(28.dp)

    /** Fully circular — action buttons, FABs, avatars */
    val Full: Shape = CircleShape
}
