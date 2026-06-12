package dev.chirpboard.app.feature.keyboard.service

import android.content.res.Configuration

/**
 * The configuration dimensions whose change restarts the IME input view (LIF-07).
 *
 * The rotation-survival logic used to compare only `orientation`, so a dark/light flip, a
 * font-scale change, a density change or a split-screen/freeform resize that restarted the view
 * BEFORE the service's own `onConfigurationChanged` failed both legs of the config-change check
 * and force-finalized an active dictation. Snapshotting all of these makes the fallback catch
 * every configuration-driven view restart. (Locale is intentionally omitted: the app ships
 * English-only and `Configuration.locales` is not unit-testable on the JVM.)
 */
internal data class KeyboardConfigSnapshot(
    val orientation: Int,
    val uiMode: Int,
    val fontScale: Float,
    val densityDpi: Int,
    val screenWidthDp: Int,
    val screenHeightDp: Int,
)

internal fun keyboardConfigSnapshotOf(configuration: Configuration): KeyboardConfigSnapshot =
    KeyboardConfigSnapshot(
        orientation = configuration.orientation,
        uiMode = configuration.uiMode,
        fontScale = configuration.fontScale,
        densityDpi = configuration.densityDpi,
        screenWidthDp = configuration.screenWidthDp,
        screenHeightDp = configuration.screenHeightDp,
    )
