package dev.chirpboard.app.feature.recording.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class RecordingServiceStopRaceTest {
    @Test
    fun concurrentStopRequests_allowOnlyOneActiveStop() {
        val gate = StopRequestGate()
        val allowedCount = AtomicInteger(0)
        val latch = CountDownLatch(2)

        repeat(2) {
            Thread {
                if (gate.tryBegin()) {
                    allowedCount.incrementAndGet()
                }
                latch.countDown()
            }.start()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(1, allowedCount.get())
    }
}
