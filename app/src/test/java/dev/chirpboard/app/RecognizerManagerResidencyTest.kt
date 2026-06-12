package dev.chirpboard.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * LOAD-1 / KBD-1: the shared recognizer starts non-resident and releasing when nothing is loaded is
 * a safe no-op, so the memory-pressure release path can never crash or churn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecognizerManagerResidencyTest {
    @Test
    fun `recognizer is not resident before any initialization`() {
        assertFalse(RecognizerManager.isResident())
    }

    @Test
    fun `releasing when nothing is loaded is a no-op and leaves it non-resident`() = runTest {
        RecognizerManager.releaseRecognizer()
        assertFalse(RecognizerManager.isResident())
    }
}
