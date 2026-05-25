package dev.chirpboard.app.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Chirp typography scale with selective Material 3 overrides.
 */
val ChirpTypography: Typography =
    Typography().copy(
        headlineMedium =
            Typography().headlineMedium.copy(
                fontWeight = FontWeight.Bold,
            ),
        titleMedium =
            Typography().titleMedium.copy(
                fontWeight = FontWeight.Medium,
            ),
        bodySmall =
            Typography().bodySmall.copy(
                letterSpacing = 0.2.sp,
            ),
    )

/** Large monospace-style timer for recording surfaces. */
val recordingTimerStyle: TextStyle =
    Typography().displayLarge.copy(
        fontSize = 72.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = 2.sp,
        fontFeatureSettings = "tnum",
    )
