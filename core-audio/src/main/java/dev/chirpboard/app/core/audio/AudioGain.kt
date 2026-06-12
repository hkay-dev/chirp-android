package dev.chirpboard.app.core.audio

import kotlin.math.abs
import kotlin.math.tanh

/**
 * Shared gain application with soft-knee limiting.
 *
 * Boosted samples are passed through unchanged below [SOFT_LIMIT_KNEE] and compressed
 * smoothly (tanh knee) above it, asymptotically approaching full scale. This replaces
 * hard clipping at +-1.0, which audibly distorted boosted speech and measurably hurt
 * recognition accuracy — the opposite of what the gain setting is for.
 */
object AudioGain {
    /** Normalized amplitude above which the soft limiter starts compressing. */
    const val SOFT_LIMIT_KNEE = 0.85f

    private const val PCM16_MAX = 32767f
    private const val PCM16_MIN_INT = -32768
    private const val PCM16_MAX_INT = 32767
    private const val BYTE_MASK = 0xFF

    /**
     * Soft-limits a normalized sample. Identity below the knee; tanh compression above,
     * always within (-1, 1). Monotonic and continuous at the knee.
     */
    fun softLimit(value: Float): Float {
        val magnitude = abs(value)
        if (magnitude <= SOFT_LIMIT_KNEE) return value
        val headroom = 1f - SOFT_LIMIT_KNEE
        val compressed = SOFT_LIMIT_KNEE + headroom * tanh((magnitude - SOFT_LIMIT_KNEE) / headroom)
        return if (value < 0f) -compressed else compressed
    }

    /** Applies [gain] with soft limiting to one normalized float sample. */
    fun boost(
        sample: Float,
        gain: Float,
    ): Float = if (gain == 1f) sample else softLimit(sample * gain)

    /**
     * Applies [gain] with soft limiting in place to little-endian 16-bit PCM bytes.
     * No-op when [gain] is 1.0 so the unboosted path stays bit-exact.
     */
    fun applyGainPcm16(
        buffer: ByteArray,
        sizeBytes: Int,
        gain: Float,
    ) {
        if (gain == 1f) return
        var index = 0
        while (index + 1 < sizeBytes) {
            val low = buffer[index].toInt() and BYTE_MASK
            val high = buffer[index + 1].toInt()
            val sample = ((high shl Byte.SIZE_BITS) or low).toShort().toInt()
            val boosted = softLimit(sample / PCM16_MAX * gain)
            val out = (boosted * PCM16_MAX).toInt().coerceIn(PCM16_MIN_INT, PCM16_MAX_INT)
            buffer[index] = (out and BYTE_MASK).toByte()
            buffer[index + 1] = ((out shr Byte.SIZE_BITS) and BYTE_MASK).toByte()
            index += 2
        }
    }
}
