package dev.chirpboard.app.core.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChirpThemeResolutionTest {

    private val fakeDynamicLight =
        lightColorScheme(
            primary = Color(0xFF112233),
            error = Color(0xFFAA0000),
            errorContainer = Color(0xFFAA1111),
            onErrorContainer = Color(0xFFFFEEEE),
            tertiary = Color(0xFF00AA00),
            tertiaryContainer = Color(0xFF11AA11),
            onTertiaryContainer = Color(0xFFEEFFEE),
        )

    private val fakeDynamicDark =
        lightColorScheme(primary = Color(0xFF445566))

    @Test
    fun resolveColorScheme_defaultsToBrandLight_whenDynamicOff() {
        val scheme = resolveColorScheme(dynamicColor = false, darkTheme = false, dynamicLight = null, dynamicDark = null)
        assertSame(ChirpColorScheme.Light, scheme)
    }

    @Test
    fun resolveColorScheme_usesBrandDark_whenDynamicOffAndDark() {
        val scheme = resolveColorScheme(dynamicColor = false, darkTheme = true, dynamicLight = null, dynamicDark = null)
        assertSame(ChirpColorScheme.Dark, scheme)
    }

    @Test
    fun resolveColorScheme_usesDynamicLight_whenDynamicOnAndLight() {
        val scheme =
            resolveColorScheme(
                dynamicColor = true,
                darkTheme = false,
                dynamicLight = fakeDynamicLight,
                dynamicDark = fakeDynamicDark,
            )
        assertSame(fakeDynamicLight, scheme)
    }

    @Test
    fun resolveColorScheme_usesDynamicDark_whenDynamicOnAndDark() {
        val scheme =
            resolveColorScheme(
                dynamicColor = true,
                darkTheme = true,
                dynamicLight = fakeDynamicLight,
                dynamicDark = fakeDynamicDark,
            )
        assertSame(fakeDynamicDark, scheme)
    }

    @Test
    fun resolveColorScheme_fallsBackToBrand_whenDynamicSchemesMissing() {
        // Defensive: dynamicColor requested but the platform schemes were not supplied.
        val light = resolveColorScheme(dynamicColor = true, darkTheme = false, dynamicLight = null, dynamicDark = null)
        val dark = resolveColorScheme(dynamicColor = true, darkTheme = true, dynamicLight = null, dynamicDark = null)
        assertSame(ChirpColorScheme.Light, light)
        assertSame(ChirpColorScheme.Dark, dark)
    }

    @Test
    fun resolveChirpAccents_returnsBrandLightAndDark_whenDynamicOff() {
        assertSame(BrandLightAccents, resolveChirpAccents(dynamicColor = false, darkTheme = false, dynamicScheme = null))
        assertSame(BrandDarkAccents, resolveChirpAccents(dynamicColor = false, darkTheme = true, dynamicScheme = null))
    }

    @Test
    fun resolveChirpAccents_derivesFromDynamicScheme_whenDynamicOn() {
        val accents =
            resolveChirpAccents(dynamicColor = true, darkTheme = false, dynamicScheme = fakeDynamicLight)
        assertEquals(fakeDynamicLight.error, accents.recordingLive)
        assertEquals(fakeDynamicLight.errorContainer, accents.recordingLiveContainer)
        assertEquals(fakeDynamicLight.tertiary, accents.aiAccent)
        assertEquals(fakeDynamicLight.tertiaryContainer, accents.aiAccentContainer)
    }

    @Test
    fun resolveChirpAccents_fallsBackToBrand_whenDynamicSchemeMissing() {
        // dynamicColor requested but scheme null -> safe brand fallback (matches color resolution).
        assertSame(BrandLightAccents, resolveChirpAccents(dynamicColor = true, darkTheme = false, dynamicScheme = null))
        assertSame(BrandDarkAccents, resolveChirpAccents(dynamicColor = true, darkTheme = true, dynamicScheme = null))
    }

    @Test
    fun brandRecordingAccent_isNotRawMaterialErrorRed() {
        // DECISIONS: the recording/live accent must NOT be the stock Material error red.
        assertNotEquals(ChirpColorScheme.Light.error, BrandLightAccents.recordingLive)
        assertNotEquals(ChirpColorScheme.Dark.error, BrandDarkAccents.recordingLive)
    }
}
