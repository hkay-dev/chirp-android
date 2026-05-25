package dev.chirpboard.app.core.audio

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RecordingStorageMonitorTest {
    private lateinit var context: Context
    private lateinit var monitor: RecordingStorageMonitor

    @Before
    fun setup() {
        context = mockk(relaxed = true) {
            every { filesDir.absolutePath } returns "/data/user/0/dev.chirpboard.app/files"
        }
        monitor = RecordingStorageMonitor(context)
    }

    @After
    fun tearDown() {
        unmockkObject(RecordingStorageMonitor)
    }

    @Test
    fun checkAvailableStorage_returnsCriticalWhenBelowThreshold() {
        mockkObject(RecordingStorageMonitor)
        every { RecordingStorageMonitor.availableBytes(any()) } returns RecordingStorageMonitor.CRITICAL_THRESHOLD_BYTES - 1

        val result = monitor.checkAvailableStorage()
        assertEquals(StorageCheckLevel.CRITICAL, result.level)
    }

    @Test
    fun checkAvailableStorage_returnsLowWhenBelowWarningThreshold() {
        mockkObject(RecordingStorageMonitor)
        every { RecordingStorageMonitor.availableBytes(any()) } returns RecordingStorageMonitor.LOW_THRESHOLD_BYTES - 1

        val result = monitor.checkAvailableStorage()
        assertEquals(StorageCheckLevel.LOW, result.level)
    }
}
