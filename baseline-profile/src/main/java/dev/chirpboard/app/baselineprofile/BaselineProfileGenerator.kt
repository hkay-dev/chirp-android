package dev.chirpboard.app.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

@LargeTest
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun appStartup() {
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            outputFilePrefix = "app-startup",
            includeInStartupProfile = true,
        ) {
            launchMainActivity()
        }
    }

    @Test
    fun imeAndKeyboardSettings() {
        baselineProfileRule.collect(
            packageName = TARGET_PACKAGE,
            outputFilePrefix = "ime-and-settings",
            includeInStartupProfile = false,
        ) {
            launchMainActivity()
            val originalIme = device.executeShellCommand("settings get secure default_input_method").trim()
            val enabledImes = device.executeShellCommand("ime list -s")
            val chirpWasEnabled = enabledImes.lineSequence().any { it.trim() == CHIRP_IME }
            try {
                device.executeShellCommand("ime enable $CHIRP_IME")
                device.executeShellCommand("ime set $CHIRP_IME")
                device.executeShellCommand("am start -W -n $IME_HOST_ACTIVITY")
                val editor =
                    checkNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), IME_WAIT_MS)) {
                        "IME profile host editor never appeared"
                    }
                editor.click()
                device.waitForIdle()
                waitForChirpImeView()
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

    private fun MacrobenchmarkScope.launchMainActivity() {
        pressHome()
        try {
            startActivityAndWait()
        } catch (error: IllegalStateException) {
            // One UI 8 can omit the final gfxinfo frame marker even though MainActivity is alive.
            // Keep Macrobenchmark's tracked launch and accept only that known case.
            check(device.executeShellCommand("pidof $TARGET_PACKAGE").isNotBlank()) {
                throw error
            }
            device.waitForIdle()
        }
    }

    private fun MacrobenchmarkScope.waitForChirpImeView() {
        val deadline = System.currentTimeMillis() + IME_WAIT_MS
        do {
            val state = device.executeShellCommand("dumpsys input_method")
            val chirpSelected =
                state.contains("mCurId=$CHIRP_IME") ||
                    state.contains("mSelectedMethodId=$CHIRP_IME")
            val inputViewStarted =
                state.contains("mInputViewStarted=true") || state.contains("mInputShown=true")
            if (chirpSelected && inputViewStarted) return
            Thread.sleep(IME_POLL_MS)
        } while (System.currentTimeMillis() < deadline)
        error("Chirp IME input view never became visible; refusing to generate an app-only profile")
    }

    private companion object {
        const val TARGET_PACKAGE = "dev.chirpboard.app"
        const val PROFILE_PACKAGE = "dev.chirpboard.app.baselineprofile"
        const val KEYBOARD_SETTINGS_ACTIVITY = "$TARGET_PACKAGE/.KeyboardSettingsLauncherActivity"
        const val CHIRP_IME =
            "$TARGET_PACKAGE/.feature.keyboard.service.ChirpKeyboardService"
        const val IME_HOST_ACTIVITY = "$PROFILE_PACKAGE/.ImeHostActivity"
        const val IME_WAIT_MS = 10_000L
        const val IME_POLL_MS = 100L
    }
}
