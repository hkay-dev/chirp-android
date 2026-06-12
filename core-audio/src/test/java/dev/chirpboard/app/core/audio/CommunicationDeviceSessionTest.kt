package dev.chirpboard.app.core.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import java.util.concurrent.Executor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MIC-006 — refcount, idempotency and failure-fallback discipline of the SCO/
 * communication-device session over a mocked [AudioManager]. The hard invariant under
 * test: no acquire path may ever leave the process-wide communication-device route
 * applied without a hold that the token teardown releases — an unbalanced acquire
 * strands the phone in headset routing after capture ends. End-to-end classic-BT
 * routing is an on-device checklist item (ONDEVICE.md); these tests pin only the
 * lifecycle contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommunicationDeviceSessionTest {
    private val audioManager = mockk<AudioManager>(relaxed = true)
    private val session = CommunicationDeviceSession(audioManager, Executor { it.run() })

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun scoDevice(
        id: Int = 7,
        name: String = "BT Headset",
    ): AudioDeviceInfo =
        mockk {
            every { this@mockk.id } returns id
            every { productName } returns name
        }

    @Test
    fun `acquire succeeds immediately when the platform reports the device active`() =
        runTest {
            val device = scoDevice()
            every { audioManager.setCommunicationDevice(device) } returns true
            every { audioManager.communicationDevice } returns device

            assertTrue(session.acquire(device))

            verify(exactly = 1) { audioManager.setCommunicationDevice(device) }
            verify(exactly = 0) { audioManager.clearCommunicationDevice() }
        }

    @Test
    fun `acquire waits for the change listener and succeeds when it confirms the device`() =
        runTest {
            val device = scoDevice()
            every { audioManager.setCommunicationDevice(device) } returns true
            every { audioManager.communicationDevice } returns null
            val listenerSlot = slot<AudioManager.OnCommunicationDeviceChangedListener>()
            justRun { audioManager.addOnCommunicationDeviceChangedListener(any(), capture(listenerSlot)) }

            var result: Boolean? = null
            launch { result = session.acquire(device) }
            runCurrent()
            assertTrue(listenerSlot.isCaptured)

            listenerSlot.captured.onCommunicationDeviceChanged(device)
            runCurrent()

            assertEquals(true, result)
            verify(exactly = 0) { audioManager.clearCommunicationDevice() }
            // The per-attempt activation listener never outlives the acquire. Identity is
            // asserted via a removal capture rather than matching on the captured instance
            // directly: the production listener is a SAM-converted lambda whose hidden
            // class kotlin-reflect (and therefore mockk argument packing) cannot resolve.
            val removedSlot = slot<AudioManager.OnCommunicationDeviceChangedListener>()
            verify(exactly = 1) { audioManager.removeOnCommunicationDeviceChangedListener(capture(removedSlot)) }
            assertSame(listenerSlot.captured, removedSlot.captured)
        }

    @Test
    fun `acquire times out and restores default routing when activation never happens`() =
        runTest {
            val device = scoDevice()
            every { audioManager.setCommunicationDevice(device) } returns true
            every { audioManager.communicationDevice } returns null

            assertFalse(session.acquire(device))

            // The half-applied route is rolled back, the listener removed, and no hold
            // was taken: a later release must be a no-op rather than a double clear.
            verify(exactly = 1) { audioManager.clearCommunicationDevice() }
            verify(exactly = 1) { audioManager.removeOnCommunicationDeviceChangedListener(any()) }
            session.release()
            verify(exactly = 1) { audioManager.clearCommunicationDevice() }
        }

    @Test
    fun `rejected setCommunicationDevice fails fast and restores default routing`() =
        runTest {
            val device = scoDevice()
            every { audioManager.setCommunicationDevice(device) } returns false
            every { audioManager.communicationDevice } returns null

            assertFalse(session.acquire(device))

            verify(exactly = 1) { audioManager.clearCommunicationDevice() }
        }

    @Test
    fun `setCommunicationDevice throwing is treated as a rejection`() =
        runTest {
            val device = scoDevice()
            every { audioManager.setCommunicationDevice(device) } throws IllegalArgumentException("not a communication device")
            every { audioManager.communicationDevice } returns null

            assertFalse(session.acquire(device))

            verify(exactly = 1) { audioManager.clearCommunicationDevice() }
        }

    @Test
    fun `refcounted holds clear the route only when the last release drops`() =
        runTest {
            val device = scoDevice()
            every { audioManager.setCommunicationDevice(device) } returns true
            every { audioManager.communicationDevice } returns device

            assertTrue(session.acquire(device))
            // Idempotent re-acquire of the engaged device: no second platform request.
            assertTrue(session.acquire(device))
            verify(exactly = 1) { audioManager.setCommunicationDevice(device) }

            session.release()
            verify(exactly = 0) { audioManager.clearCommunicationDevice() }
            session.release()
            verify(exactly = 1) { audioManager.clearCommunicationDevice() }
            // Over-release never clears again.
            session.release()
            verify(exactly = 1) { audioManager.clearCommunicationDevice() }
        }

    @Test
    fun `cancellation while awaiting activation restores default routing`() =
        runTest {
            val device = scoDevice()
            every { audioManager.setCommunicationDevice(device) } returns true
            every { audioManager.communicationDevice } returns null

            val job = launch { session.acquire(device) }
            runCurrent()
            job.cancel()
            runCurrent()

            assertTrue(job.isCancelled)
            verify(exactly = 1) { audioManager.clearCommunicationDevice() }
            verify(exactly = 1) { audioManager.removeOnCommunicationDeviceChangedListener(any()) }
        }

    @Test
    fun `release without any hold is a no-op`() {
        session.release()

        verify(exactly = 0) { audioManager.clearCommunicationDevice() }
    }

    @Test
    fun `failed switch attempt does not clear the route an older hold owns`() =
        runTest {
            val engaged = scoDevice(id = 7)
            every { audioManager.setCommunicationDevice(engaged) } returns true
            every { audioManager.communicationDevice } returns engaged
            assertTrue(session.acquire(engaged))

            val other = scoDevice(id = 9, name = "Other Headset")
            every { audioManager.setCommunicationDevice(other) } returns false
            assertFalse(session.acquire(other))

            // The older hold still owns the route; only its own release clears it.
            verify(exactly = 0) { audioManager.clearCommunicationDevice() }
            session.release()
            verify(exactly = 1) { audioManager.clearCommunicationDevice() }
        }
}
