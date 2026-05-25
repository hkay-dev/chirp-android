package dev.chirpboard.app.feature.keyboard.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import dev.chirpboard.app.core.ui.theme.ChirpTheme

@Composable
fun KeyboardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) = ChirpTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, content = content)
