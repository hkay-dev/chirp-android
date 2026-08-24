package dev.chirpboard.app.core.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.chirpboard.app.core.ui.components.LocalReducedMotion
import dev.chirpboard.app.core.ui.components.readReducedMotion

private val chirpMaterialShapes =
    Shapes(
        extraSmall = ChirpShapes.ExtraSmall as CornerBasedShape,
        small = ChirpShapes.Small as CornerBasedShape,
        medium = ChirpShapes.Medium as CornerBasedShape,
        large = ChirpShapes.Large as CornerBasedShape,
        extraLarge = ChirpShapes.ExtraLarge as CornerBasedShape,
    )

/**
 * Resolve the active Material [ColorScheme].
 *
 * Brand palette is the default; dynamic (Material You) is opt-in. Extracted so the resolution
 * order is unit-testable without a Compose runtime — pass the [dynamicLight]/[dynamicDark] schemes
 * (or nulls when dynamic color is off, since deriving them needs a [Context]).
 */
internal fun resolveColorScheme(
    dynamicColor: Boolean,
    darkTheme: Boolean,
    dynamicLight: ColorScheme?,
    dynamicDark: ColorScheme?,
): ColorScheme =
    when {
        dynamicColor && darkTheme && dynamicDark != null -> dynamicDark
        dynamicColor && dynamicLight != null -> dynamicLight
        darkTheme -> ChirpColorScheme.Dark
        else -> ChirpColorScheme.Light
    }

/**
 * App theme.
 *
 * @param dynamicColor when true, derive colors from the wallpaper (Material You). Defaults to
 *   FALSE so the brand lavender palette is used for cohesion (DECISIONS Color/brand). A Settings
 *   toggle backed by [DynamicColorPreference] flips this; the activity collects the preference and
 *   passes the value here.
 *
 * Also publishes the resolved [ChirpAccents] via [LocalChirpAccents] so semantic recording/AI
 * accents resolve correctly for the active light/dark + brand/dynamic combination. Read them with
 * `MaterialTheme.colorScheme.chirpAccents`.
 */
@Composable
fun ChirpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context: Context = LocalContext.current
    // Remember so theme-scope recompositions (e.g. each keyboard IME phase transition) do not
    // re-derive the full dynamic ColorScheme; context is a key in case the configuration changes.
    val colorScheme =
        remember(darkTheme, dynamicColor, context) {
            val dynamicLight = if (dynamicColor) dynamicLightColorScheme(context) else null
            val dynamicDark = if (dynamicColor) dynamicDarkColorScheme(context) else null
            resolveColorScheme(dynamicColor, darkTheme, dynamicLight, dynamicDark)
        }

    val accents =
        remember(darkTheme, dynamicColor, colorScheme) {
            resolveChirpAccents(
                dynamicColor = dynamicColor,
                darkTheme = darkTheme,
                dynamicScheme = if (dynamicColor) colorScheme else null,
            )
        }

    // Resolved once per theme root: the reduced-motion read hits a settings provider (a binder
    // call), and a skeleton screen can host a dozen motion affordances.
    val reducedMotion = remember(context) { readReducedMotion(context) }

    CompositionLocalProvider(
        LocalChirpAccents provides accents,
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ChirpTypography,
            shapes = chirpMaterialShapes,
            content = content,
        )
    }
}
