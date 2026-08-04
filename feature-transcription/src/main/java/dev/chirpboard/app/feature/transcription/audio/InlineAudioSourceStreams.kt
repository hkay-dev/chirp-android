package dev.chirpboard.app.feature.transcription.audio

import dev.chirpboard.app.core.transcription.InlineAudioSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val INLINE_AUDIO_SOURCE_CHUNK_SAMPLES = 16_000

internal fun InlineAudioSource.asSampleFlow(
    chunkSamples: Int = INLINE_AUDIO_SOURCE_CHUNK_SAMPLES,
): Flow<FloatArray> =
    when (this) {
        is InlineAudioSource.InMemory -> flow { emit(samples) }
        is InlineAudioSource.PcmFloatFile -> rawPcmFloatFileAsFlow(path, chunkSamples)
    }

internal fun InlineAudioSource.totalSamples(): Long =
    when (this) {
        is InlineAudioSource.InMemory -> samples.size.toLong()
        is InlineAudioSource.PcmFloatFile -> sampleCount
    }

/**
 * Reads a bounded source as one continuous utterance. Callers must apply their native-model
 * memory limit before using this helper.
 */
internal suspend fun InlineAudioSource.readAllSamples(): FloatArray =
    when (this) {
        is InlineAudioSource.InMemory -> samples
        is InlineAudioSource.PcmFloatFile -> {
            require(sampleCount <= Int.MAX_VALUE) { "PCM source is too large for one utterance" }
            val result = FloatArray(sampleCount.toInt())
            var offset = 0
            asSampleFlow().collect { chunk ->
                if (offset + chunk.size > result.size) {
                    throw IOException("Raw float PCM file contains more samples than declared")
                }
                chunk.copyInto(result, destinationOffset = offset)
                offset += chunk.size
            }
            if (offset != result.size) {
                throw IOException("Raw float PCM file contains fewer samples than declared")
            }
            result
        }
    }

fun InlineAudioSource.discardTemporaryFile() {
    if (this is InlineAudioSource.PcmFloatFile) {
        runCatching { File(path).delete() }
    }
}

internal fun rawPcmFloatFileAsFlow(
    path: String,
    chunkSamples: Int,
): Flow<FloatArray> =
    flow {
        require(chunkSamples > 0) { "chunkSamples must be positive" }
        val readBuffer = ByteArray(chunkSamples * java.lang.Float.BYTES)
        FileInputStream(path).use { input ->
            var carriedBytes = 0
            while (true) {
                val bytesRead = input.read(readBuffer, carriedBytes, readBuffer.size - carriedBytes)
                if (bytesRead < 0) {
                    break
                }
                if (bytesRead == 0) continue

                val availableBytes = carriedBytes + bytesRead
                val floatCount = availableBytes / java.lang.Float.BYTES
                if (floatCount <= 0) {
                    carriedBytes = availableBytes
                    continue
                }
                val decodedBytes = floatCount * java.lang.Float.BYTES
                val byteBuffer = ByteBuffer.wrap(readBuffer, 0, decodedBytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                val samples = FloatArray(floatCount)
                for (index in 0 until floatCount) {
                    samples[index] = byteBuffer.float
                }
                emit(samples)

                carriedBytes = availableBytes - decodedBytes
                if (carriedBytes > 0) {
                    readBuffer.copyInto(
                        destination = readBuffer,
                        destinationOffset = 0,
                        startIndex = decodedBytes,
                        endIndex = availableBytes,
                    )
                }
            }
            if (carriedBytes != 0) {
                throw IOException("Raw float PCM file ends with an incomplete sample")
            }
        }
    }.flowOn(Dispatchers.IO)
