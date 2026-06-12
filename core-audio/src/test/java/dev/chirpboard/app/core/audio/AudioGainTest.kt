package dev.chirpboard.app.core.audio

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioGainTest {
    @Test
    fun `softLimit is identity below the knee`() {
        assertEquals(0.5f, AudioGain.softLimit(0.5f), 0f)
        assertEquals(-0.5f, AudioGain.softLimit(-0.5f), 0f)
        assertEquals(AudioGain.SOFT_LIMIT_KNEE, AudioGain.softLimit(AudioGain.SOFT_LIMIT_KNEE), 0f)
        assertEquals(0f, AudioGain.softLimit(0f), 0f)
    }

    @Test
    fun `softLimit never exceeds full scale above the knee`() {
        for (value in listOf(0.9f, 1.0f, 2.0f, 5.0f, 100f)) {
            val limited = AudioGain.softLimit(value)
            // tanh saturates to 1.0f in float precision for extreme inputs; the contract
            // is never exceeding full scale (no wrap/clip), not staying strictly below.
            assertTrue("softLimit($value)=$limited", limited <= 1f)
            assertTrue(limited > AudioGain.SOFT_LIMIT_KNEE)
            assertEquals(-limited, AudioGain.softLimit(-value), 1e-6f)
        }
        // Moderate overdrive keeps real headroom below full scale.
        assertTrue(AudioGain.softLimit(1.2f) < 1f)
    }

    @Test
    fun `softLimit is monotonic and continuous at the knee`() {
        var previous = AudioGain.softLimit(0f)
        var value = 0.01f
        while (value < 3f) {
            val current = AudioGain.softLimit(value)
            assertTrue("not monotonic at $value", current >= previous)
            previous = current
            value += 0.01f
        }
        val justBelow = AudioGain.softLimit(AudioGain.SOFT_LIMIT_KNEE - 1e-4f)
        val justAbove = AudioGain.softLimit(AudioGain.SOFT_LIMIT_KNEE + 1e-4f)
        assertTrue(abs(justAbove - justBelow) < 1e-3f)
    }

    @Test
    fun `boost at unity gain is bit-exact passthrough`() {
        assertEquals(0.999f, AudioGain.boost(0.999f, 1f), 0f)
        assertEquals(-1f, AudioGain.boost(-1f, 1f), 0f)
    }

    @Test
    fun `applyGainPcm16 is a no-op at unity gain`() {
        val buffer = byteArrayOf(0x34, 0x12, 0x88.toByte(), 0xFF.toByte())
        val original = buffer.copyOf()

        AudioGain.applyGainPcm16(buffer, buffer.size, 1f)

        assertTrue(buffer.contentEquals(original))
    }

    @Test
    fun `applyGainPcm16 doubles quiet samples without clipping loud ones`() {
        // Sample 1: quiet (1000); sample 2: loud (30000, near full scale).
        val buffer =
            byteArrayOf(
                (1000 and 0xFF).toByte(),
                (1000 shr 8).toByte(),
                (30000 and 0xFF).toByte(),
                (30000 shr 8).toByte(),
            )

        AudioGain.applyGainPcm16(buffer, buffer.size, 2f)

        val quiet = ((buffer[1].toInt() shl 8) or (buffer[0].toInt() and 0xFF)).toShort().toInt()
        val loud = ((buffer[3].toInt() shl 8) or (buffer[2].toInt() and 0xFF)).toShort().toInt()
        assertEquals(2000, quiet)
        // Soft-limited: boosted but strictly below full scale, never wrapped negative.
        assertTrue("loud=$loud", loud in 30000 until 32767)
    }

    @Test
    fun `applyGainPcm16 keeps zero samples at zero`() {
        val buffer = ByteArray(8)

        AudioGain.applyGainPcm16(buffer, buffer.size, 5f)

        assertTrue(buffer.all { it == 0.toByte() })
    }
}
