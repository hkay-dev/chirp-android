package dev.chirpboard.app.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val chirpMaterialShapes =
    Shapes(
        extraSmall = ChirpShapes.ExtraSmall as CornerBasedShape,
        small = ChirpShapes.Small as CornerBasedShape,
        medium = ChirpShapes.Medium as CornerBasedShape,
        large = ChirpShapes.Large as CornerBasedShape,
        extraLarge = ChirpShapes.ExtraLarge as CornerBasedShape,
    )

@Composable
fun ChirpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) 
            else dynamicLightColorScheme(context)
        }
        darkTheme -> ChirpColorScheme.Dark
        else -> ChirpColorScheme.Light
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ChirpTypography,
        shapes = chirpMaterialShapes,
        content = content
    )
}
