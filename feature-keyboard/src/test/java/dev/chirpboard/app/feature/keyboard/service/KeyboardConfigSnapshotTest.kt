package dev.chirpboard.app.feature.keyboard.service

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class KeyboardConfigSnapshotTest {
    private fun baseConfiguration(): Configuration =
        Configuration().apply {
            orientation = Configuration.ORIENTATION_PORTRAIT
            uiMode = Configuration.UI_MODE_NIGHT_NO
            fontScale = 1.0f
            densityDpi = 420
            screenWidthDp = 411
            screenHeightDp = 891
        }

    @Test
    fun `identical configurations produce equal snapshots`() {
        assertEquals(
            keyboardConfigSnapshotOf(baseConfiguration()),
            keyboardConfigSnapshotOf(baseConfiguration()),
        )
    }

    @Test
    fun `orientation change is detected`() {
        val rotated = baseConfiguration().apply { orientation = Configuration.ORIENTATION_LANDSCAPE }
        assertNotEquals(keyboardConfigSnapshotOf(baseConfiguration()), keyboardConfigSnapshotOf(rotated))
    }

    @Test
    fun `dark mode flip is detected`() {
        // LIF-07: the dark/light flip must count as a config change so a mid-dictation theme
        // switch (auto theme at sunset) preserves the session like rotation does.
        val dark = baseConfiguration().apply { uiMode = Configuration.UI_MODE_NIGHT_YES }
        assertNotEquals(keyboardConfigSnapshotOf(baseConfiguration()), keyboardConfigSnapshotOf(dark))
    }

    @Test
    fun `font scale change is detected`() {
        val scaled = baseConfiguration().apply { fontScale = 2.0f }
        assertNotEquals(keyboardConfigSnapshotOf(baseConfiguration()), keyboardConfigSnapshotOf(scaled))
    }

    @Test
    fun `split screen resize is detected`() {
        val resized = baseConfiguration().apply { screenHeightDp = 445 }
        assertNotEquals(keyboardConfigSnapshotOf(baseConfiguration()), keyboardConfigSnapshotOf(resized))
    }

    @Test
    fun `density change is detected`() {
        val densityChanged = baseConfiguration().apply { densityDpi = 480 }
        assertNotEquals(keyboardConfigSnapshotOf(baseConfiguration()), keyboardConfigSnapshotOf(densityChanged))
    }
}
