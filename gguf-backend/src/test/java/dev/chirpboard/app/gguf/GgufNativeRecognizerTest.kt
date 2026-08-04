package dev.chirpboard.app.gguf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufNativeRecognizerTest {
    @Test
    fun `native timing payload maps every content free stage`() {
        val result = floatArrayOf(10f, 20f, 30f, 40f, 1f, 13f).toDecodeTelemetry()

        assertEquals(10f, result?.loadMs)
        assertEquals(20f, result?.melMs)
        assertEquals(30f, result?.encodeMs)
        assertEquals(40f, result?.decodeMs)
        assertTrue(result?.aborted == true)
        assertEquals(13, result?.statusCode)
    }

    @Test
    fun `malformed native timing payload is rejected`() {
        assertNull(floatArrayOf(1f, 2f).toDecodeTelemetry())
        assertNull(floatArrayOf(1f, Float.NaN, 3f, 4f, 0f, 0f).toDecodeTelemetry())
        assertNull(floatArrayOf(1f, -2f, 3f, 4f, 0f, 0f).toDecodeTelemetry())
    }
}
