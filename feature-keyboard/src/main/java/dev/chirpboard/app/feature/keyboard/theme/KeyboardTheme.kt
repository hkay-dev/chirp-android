package dev.chirpboard.app.feature.keyboard.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import dev.chirpboard.app.core.ui.theme.ChirpTheme

@Composable
fun KeyboardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Brand lavender palette is the default for cohesion (DECISIONS Color/brand); a later wave can
    // pass the user's "Use system colors" preference through to opt into Material You.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) = ChirpTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, content = content)
