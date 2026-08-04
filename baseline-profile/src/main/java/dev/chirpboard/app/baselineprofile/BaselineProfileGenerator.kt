package dev.chirpboard.app.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test

@LargeTest
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun criticalUserJourneys() {
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = true,
        ) {
            pressHome()
            try {
                startActivityAndWait()
            } catch (error: IllegalStateException) {
                // One UI 8 can omit the final gfxinfo frame marker even though MainActivity is
                // alive. Keep Macrobenchmark's tracked launch and accept only that known case.
                check(device.executeShellCommand("pidof $TARGET_PACKAGE").isNotBlank()) {
                    throw error
                }
                device.waitForIdle()
            }

            val originalIme = device.executeShellCommand("settings get secure default_input_method").trim()
            val enabledImes = device.executeShellCommand("ime list -s")
            val chirpWasEnabled = enabledImes.lineSequence().any { it.trim() == CHIRP_IME }
            try {
                device.executeShellCommand("ime enable $CHIRP_IME")
                device.executeShellCommand("ime set $CHIRP_IME")
                device.executeShellCommand("am start -W -n $IME_HOST_ACTIVITY")
                device.waitForIdle()
            } finally {
                if (originalIme.isNotBlank() && originalIme != "null") {
                    device.executeShellCommand("ime set $originalIme")
                }
                if (!chirpWasEnabled) {
                    device.executeShellCommand("ime disable $CHIRP_IME")
                }
                pressHome()
            }

            device.executeShellCommand("am start -W -n $KEYBOARD_SETTINGS_ACTIVITY")
            device.waitForIdle()
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "dev.chirpboard.app"
        const val PROFILE_PACKAGE = "dev.chirpboard.app.baselineprofile"
        const val KEYBOARD_SETTINGS_ACTIVITY = "$TARGET_PACKAGE/.KeyboardSettingsLauncherActivity"
        const val CHIRP_IME =
            "$TARGET_PACKAGE/dev.chirpboard.app.feature.keyboard.service.ChirpKeyboardService"
        const val IME_HOST_ACTIVITY = "$PROFILE_PACKAGE/.ImeHostActivity"
    }
}
