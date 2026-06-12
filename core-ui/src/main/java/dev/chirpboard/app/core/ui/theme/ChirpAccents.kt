package dev.chirpboard.app.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic accent tokens layered on top of the Material [ColorScheme].
 *
 * These exist because two app-wide roles are NOT well served by the stock Material roles:
 *  - **recording / live**: every "we are capturing audio" surface (keyboard glow + waveform, the
 *    recognition dialog, the home live row, the record screen). The stock choice was
 *    `colorScheme.error` (raw red), which reads as "danger" rather than "live" and clashes with the
 *    lavender brand. [recordingLive] is a cohesive warm coral that still reads as "recording".
 *  - **AI / LLM**: the sparkle toggle, the AI Processing settings entry and the dialog's AI control.
 *    [aiAccent] gives one consistent hue so the AI affordance is recognisable across surfaces.
 *
 * Each role has a foreground accent plus a `*Container` tonal companion (for filled affordances)
 * and an `on*Container` content color, mirroring the Material container/onContainer pairing.
 *
 * Resolve via [ColorScheme.chirpAccents] (or the [LocalChirpAccents] composition local) so the
 * correct light/dark + brand/dynamic variant is returned automatically by [ChirpTheme].
 */
data class ChirpAccents(
    /** Foreground "live/recording" accent — waveform bars, the live dot, the active timer text. */
    val recordingLive: Color,
    /** Filled container tone for recording affordances (e.g. a record/stop button background). */
    val recordingLiveContainer: Color,
    /** Content color drawn on top of [recordingLiveContainer]. */
    val onRecordingLiveContainer: Color,
    /** Foreground "AI/LLM" accent — sparkle glyph tint, AI labels. */
    val aiAccent: Color,
    /** Filled container tone for the active AI affordance (e.g. the enabled sparkle toggle). */
    val aiAccentContainer: Color,
    /** Content color drawn on top of [aiAccentContainer]. */
    val onAiAccentContainer: Color,
)

/**
 * Brand accents for the static Chirpboard palette (the lavender/purple identity).
 *
 * The recording accent is a warm coral that is distinct from the lavender brand yet softer than the
 * stock Material error red; the AI accent reuses the brand's tertiary/violet family so the sparkle
 * stays inside the brand world.
 */
internal val BrandLightAccents =
    ChirpAccents(
        recordingLive = Color(0xFFC4314B),
        recordingLiveContainer = Color(0xFFFFD9DD),
        onRecordingLiveContainer = Color(0xFF40000A),
        aiAccent = Color(0xFF7A4FC9),
        aiAccentContainer = Color(0xFFEADDFF),
        onAiAccentContainer = Color(0xFF25005A),
    )

internal val BrandDarkAccents =
    ChirpAccents(
        recordingLive = Color(0xFFFFB2B8),
        recordingLiveContainer = Color(0xFF8C2638),
        onRecordingLiveContainer = Color(0xFFFFD9DD),
        aiAccent = Color(0xFFD3BBFF),
        aiAccentContainer = Color(0xFF4F378B),
        onAiAccentContainer = Color(0xFFEADDFF),
    )

/**
 * Derive accents from an arbitrary (typically dynamic / Material You) [ColorScheme].
 *
 * Dynamic schemes have no fixed brand hue, so the recording accent leans on the scheme's `error`
 * family (the only universally "alerting" role) while the AI accent leans on `tertiary` — both
 * already wallpaper-tuned, keeping the accents cohesive with the user's chosen palette.
 */
internal fun ColorScheme.deriveDynamicAccents(): ChirpAccents =
    ChirpAccents(
        recordingLive = error,
        recordingLiveContainer = errorContainer,
        onRecordingLiveContainer = onErrorContainer,
        aiAccent = tertiary,
        aiAccentContainer = tertiaryContainer,
        onAiAccentContainer = onTertiaryContainer,
    )

/**
 * CompositionLocal carrying the resolved [ChirpAccents] for the current theme. Populated by
 * [ChirpTheme]; reading it outside a [ChirpTheme] yields [BrandLightAccents] as a safe default.
 */
val LocalChirpAccents = staticCompositionLocalOf { BrandLightAccents }

/**
 * Resolved Chirpboard accents for the current theme.
 *
 * Usage: `MaterialTheme.colorScheme.chirpAccents.recordingLive`. The receiver disambiguates the
 * extension and reads from [LocalChirpAccents].
 */
val ColorScheme.chirpAccents: ChirpAccents
    @Composable
    @ReadOnlyComposable
    get() = LocalChirpAccents.current

/**
 * Resolve the accents for a given mode without a composition (testable, and used by [ChirpTheme]).
 *
 * @param dynamicScheme the active dynamic scheme when [dynamicColor] is true; ignored otherwise.
 */
internal fun resolveChirpAccents(
    dynamicColor: Boolean,
    darkTheme: Boolean,
    dynamicScheme: ColorScheme?,
): ChirpAccents =
    when {
        dynamicColor && dynamicScheme != null -> dynamicScheme.deriveDynamicAccents()
        darkTheme -> BrandDarkAccents
        else -> BrandLightAccents
    }
