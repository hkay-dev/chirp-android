package dev.chirpboard.app

import dev.chirpboard.app.ChirpApplication.TrimMemoryAction
import dev.chirpboard.app.ChirpApplication.ThermalStatusAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LOAD-1 / KBD-1 / PRF-1 / REL-09: the shared ~660MB recognizer is kept warm while the keyboard is
 * enabled and is freed ONLY under genuine OS memory pressure or severe thermal pressure. This
 * pins the trim-level mapping:
 *  - the legacy genuine-pressure levels (undelivered since API 34, kept best-effort) release
 *    immediately;
 *  - the ONLY levels Android 16 actually delivers (UI_HIDDEN / BACKGROUND) fire constantly between
 *    dictations, so they may release ONLY after a getMemoryInfo() poll confirms real pressure —
 *    never unconditionally, which would regress into the LOAD-1 cold model reload;
 *  - the moderate levels never release.
 *
 * Values mirror the documented `android.content.ComponentCallbacks2` constants. They are used as
 * literals here so the policy is verified without relying on the android.jar stub in a JVM test.
 */
class MemoryPressureRecognizerReleaseTest {
    private companion object {
        const val TRIM_MEMORY_RUNNING_MODERATE = 5
        const val TRIM_MEMORY_RUNNING_LOW = 10
        const val TRIM_MEMORY_RUNNING_CRITICAL = 15
        const val TRIM_MEMORY_UI_HIDDEN = 20
        const val TRIM_MEMORY_BACKGROUND = 40
        const val TRIM_MEMORY_MODERATE = 60
        const val TRIM_MEMORY_COMPLETE = 80
    }

    @Test
    fun `legacy genuine-pressure levels release immediately if unused`() {
        assertEquals(
            TrimMemoryAction.RELEASE_IF_UNUSED,
            ChirpApplication.trimMemoryAction(TRIM_MEMORY_RUNNING_LOW),
        )
        assertEquals(
            TrimMemoryAction.RELEASE_IF_UNUSED,
            ChirpApplication.trimMemoryAction(TRIM_MEMORY_RUNNING_CRITICAL),
        )
        assertEquals(
            TrimMemoryAction.RELEASE_IF_UNUSED,
            ChirpApplication.trimMemoryAction(TRIM_MEMORY_COMPLETE),
        )
    }

    @Test
    fun `delivered routine levels require a confirmed low-memory poll`() {
        // These fire constantly (the keyboard goes UI-hidden between dictations); trimming here
        // unconditionally would reload the model on the next dictation — exactly the LOAD-1 bug.
        // They are still the only levels Android 16 delivers, so they are the polling hook.
        assertEquals(
            TrimMemoryAction.RELEASE_IF_SYSTEM_LOW,
            ChirpApplication.trimMemoryAction(TRIM_MEMORY_UI_HIDDEN),
        )
        assertEquals(
            TrimMemoryAction.RELEASE_IF_SYSTEM_LOW,
            ChirpApplication.trimMemoryAction(TRIM_MEMORY_BACKGROUND),
        )
    }

    @Test
    fun `moderate levels keep the model warm`() {
        assertEquals(
            TrimMemoryAction.KEEP,
            ChirpApplication.trimMemoryAction(TRIM_MEMORY_RUNNING_MODERATE),
        )
        assertEquals(
            TrimMemoryAction.KEEP,
            ChirpApplication.trimMemoryAction(TRIM_MEMORY_MODERATE),
        )
    }

    @Test
    fun `severe thermal state releases only an unused recognizer`() {
        assertEquals(ThermalStatusAction.KEEP, ChirpApplication.thermalStatusAction(0))
        assertEquals(ThermalStatusAction.KEEP, ChirpApplication.thermalStatusAction(2))
        assertEquals(
            ThermalStatusAction.RELEASE_IF_UNUSED,
            ChirpApplication.thermalStatusAction(3),
        )
        assertEquals(
            ThermalStatusAction.RELEASE_IF_UNUSED,
            ChirpApplication.thermalStatusAction(6),
        )
    }
}
