package dev.chirpboard.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Streams an exact little-endian float32 capture in bounded arrays for recovery decode. */
internal fun preservedPcmFloatFlow(
    path: String,
    sampleCount: Long,
    sliceSamples: Int,
): Flow<FloatArray> =
    flow {
        require(sampleCount > 0L)
        require(sliceSamples > 0)
        val expectedBytes = Math.multiplyExact(sampleCount, Float.SIZE_BYTES.toLong())
        val file = File(path)
        require(file.isFile && file.length() == expectedBytes) {
            "Preserved PCM length does not match its trusted sample count"
        }

        RandomAccessFile(file, "r").use { input ->
            var remaining = sampleCount
            val byteBuffer =
                ByteBuffer.allocate(sliceSamples * Float.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
            while (remaining > 0L) {
                val count = minOf(remaining, sliceSamples.toLong()).toInt()
                val bytes = count * Float.SIZE_BYTES
                input.readFully(byteBuffer.array(), 0, bytes)
                byteBuffer.clear()
                val samples = FloatArray(count)
                repeat(count) { index -> samples[index] = byteBuffer.getFloat() }
                emit(samples)
                remaining -= count
            }
        }
    }
