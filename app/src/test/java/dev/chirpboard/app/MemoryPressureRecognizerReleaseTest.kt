package dev.chirpboard.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LOAD-1 / KBD-1: the shared ~660MB recognizer is kept warm while the keyboard is enabled and is
 * freed ONLY under genuine OS memory pressure. This pins exactly which trim levels release it, so a
 * routine background-LRU or UI-hidden trim (which happens constantly between dictations) can never
 * regress into the cold model reload the user complained about.
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
    fun `releases under genuine running and complete pressure`() {
        assertTrue(ChirpApplication.shouldReleaseRecognizerOnTrim(TRIM_MEMORY_RUNNING_LOW))
        assertTrue(ChirpApplication.shouldReleaseRecognizerOnTrim(TRIM_MEMORY_RUNNING_CRITICAL))
        assertTrue(ChirpApplication.shouldReleaseRecognizerOnTrim(TRIM_MEMORY_COMPLETE))
    }

    @Test
    fun `keeps the model warm on routine background and ui-hidden trims`() {
        // These fire constantly (the keyboard goes UI-hidden between dictations); trimming here
        // would reload the model on the next dictation — exactly the LOAD-1 bug.
        assertFalse(ChirpApplication.shouldReleaseRecognizerOnTrim(TRIM_MEMORY_RUNNING_MODERATE))
        assertFalse(ChirpApplication.shouldReleaseRecognizerOnTrim(TRIM_MEMORY_UI_HIDDEN))
        assertFalse(ChirpApplication.shouldReleaseRecognizerOnTrim(TRIM_MEMORY_BACKGROUND))
        assertFalse(ChirpApplication.shouldReleaseRecognizerOnTrim(TRIM_MEMORY_MODERATE))
    }
}
