package dev.chirpboard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StreamingSherpaRecognizerTest {
    @Test
    fun `offline thread selection leaves cores for capture and UI`() {
        assertEquals(1, adaptiveOfflineThreadCount(1, lowRamDevice = false))
        assertEquals(2, adaptiveOfflineThreadCount(4, lowRamDevice = false))
        assertEquals(3, adaptiveOfflineThreadCount(6, lowRamDevice = false))
        assertEquals(4, adaptiveOfflineThreadCount(8, lowRamDevice = false))
        assertEquals(2, adaptiveOfflineThreadCount(12, lowRamDevice = true))
    }

    @Test
    fun `streaming model manifest has the expected bounded footprint`() {
        assertEquals(43_649_301L, StreamingModelStore.FILES.sumOf { it.size })
        assertEquals(4, StreamingModelStore.FILES.size)
    }

    @Test
    fun `model file validation checks size and digest`() {
        val file = File.createTempFile("chirp-streaming", ".model")
        try {
            file.writeText("preview")
            val valid =
                StreamingModelFile(
                    name = file.name,
                    size = 7,
                    sha256 = "5975cf1bba432391c94667f5886225f69377c0aa8b9fa21fddfb21c89bcf9092",
                )
            assertTrue(valid.isValidFile(file))
            assertFalse(valid.copy(size = 8).isValidFile(file))
            assertFalse(valid.copy(sha256 = "0".repeat(64)).isValidFile(file))
        } finally {
            file.delete()
        }
    }
}
