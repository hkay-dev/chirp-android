package dev.chirpboard.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * LOAD-1 / KBD-1 / PRF-2: the shared recognizer starts non-resident, releasing when nothing is
 * loaded is a safe no-op, and the usage-lease bookkeeping that protects in-flight transcriptions
 * from the idle/pressure release paths balances under success and failure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecognizerManagerResidencyTest {
    @Before
    fun resetState() {
        RecognizerManager.resetUsageStateForTest()
    }

    @After
    fun cleanup() {
        RecognizerManager.resetUsageStateForTest()
    }

    @Test
    fun `recognizer is not resident before any initialization`() {
        assertFalse(RecognizerManager.isResident())
    }

    @Test
    fun `releasing when nothing is loaded is a no-op and leaves it non-resident`() = runTest {
        RecognizerManager.releaseRecognizer()
        assertFalse(RecognizerManager.isResident())
    }

    @Test
    fun `conditional release on a non-resident manager reports NOT_RESIDENT`() = runTest {
        val decision =
            RecognizerManager.releaseIfUnused(
                minIdleMs = 0L,
                nowMs = System.currentTimeMillis(),
                isExternallyBusy = { false },
            )
        assertEquals(IdleReleaseDecision.NOT_RESIDENT, decision)
    }

    @Test
    fun `usage lease is held for the duration of the block and stamps recency`() = runTest {
        assertEquals(0, RecognizerManager.activeLeaseCount())
        assertEquals(0L, RecognizerManager.lastUsedAtMs())

        RecognizerManager.withUsageLease {
            assertEquals(1, RecognizerManager.activeLeaseCount())
            assertTrue(RecognizerManager.lastUsedAtMs() > 0L)
        }

        assertEquals(0, RecognizerManager.activeLeaseCount())
        assertTrue(RecognizerManager.lastUsedAtMs() > 0L)
    }

    @Test
    fun `usage lease is returned even when the block throws`() = runTest {
        runCatching {
            RecognizerManager.withUsageLease<Unit> { error("decode blew up") }
        }
        assertEquals(0, RecognizerManager.activeLeaseCount())
    }

    @Test
    fun `nested leases balance back to zero`() = runTest {
        RecognizerManager.withUsageLease {
            RecognizerManager.withUsageLease {
                assertEquals(2, RecognizerManager.activeLeaseCount())
            }
            assertEquals(1, RecognizerManager.activeLeaseCount())
        }
        assertEquals(0, RecognizerManager.activeLeaseCount())
    }
}
