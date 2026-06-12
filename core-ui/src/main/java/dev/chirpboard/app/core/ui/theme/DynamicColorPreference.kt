package dev.chirpboard.app.core.ui.theme

import kotlinx.coroutines.flow.Flow

/**
 * Persisted "Use system colors (Material You)" preference.
 *
 * DECISIONS (Color/brand): the brand lavender palette is the DEFAULT (dynamicColor = false). Power
 * users can opt into wallpaper-derived dynamic color via a Settings toggle. This interface is the
 * seam between that Settings row (the writer) and [ChirpTheme] (the reader); the concrete
 * DataStore/SharedPreferences implementation is supplied by the app module so core-ui keeps no
 * storage dependency.
 *
 * A later wave wires:
 *  - an implementation backed by the existing preferences mechanism,
 *  - a Settings "Appearance" row that calls [setUseDynamicColor],
 *  - and surfaces [useDynamicColor] to the activity, passing the collected value into
 *    `ChirpTheme(dynamicColor = …)`.
 */
interface DynamicColorPreference {
    /** Emits the current preference. Defaults to [DEFAULT_USE_DYNAMIC_COLOR] when unset. */
    val useDynamicColor: Flow<Boolean>

    /** Persist the user's choice. */
    suspend fun setUseDynamicColor(enabled: Boolean)

    companion object {
        /** Brand palette is the default for cohesion (DECISIONS Color/brand). */
        const val DEFAULT_USE_DYNAMIC_COLOR: Boolean = false
    }
}
