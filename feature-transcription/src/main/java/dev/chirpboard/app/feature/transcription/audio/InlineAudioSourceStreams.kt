package dev.chirpboard.app.feature.transcription.audio

import dev.chirpboard.app.core.transcription.InlineAudioSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val INLINE_AUDIO_SOURCE_CHUNK_SAMPLES = 16_000

internal fun InlineAudioSource.asSampleFlow(
    chunkSamples: Int = INLINE_AUDIO_SOURCE_CHUNK_SAMPLES,
): Flow<FloatArray> =
    when (this) {
        is InlineAudioSource.InMemory -> flow { emit(samples) }
        is InlineAudioSource.PcmFloatFile -> pcmFloatFileAsFlow(path, chunkSamples)
    }

internal fun InlineAudioSource.totalSamples(): Long =
    when (this) {
        is InlineAudioSource.InMemory -> samples.size.toLong()
        is InlineAudioSource.PcmFloatFile -> sampleCount
    }

fun InlineAudioSource.discardTemporaryFile() {
    if (this is InlineAudioSource.PcmFloatFile) {
        runCatching { File(path).delete() }
    }
}

private fun pcmFloatFileAsFlow(
    path: String,
    chunkSamples: Int,
): Flow<FloatArray> =
    flow {
        val readBuffer = ByteArray(chunkSamples * java.lang.Float.BYTES)
        FileInputStream(path).use { input ->
            while (true) {
                val bytesRead = input.read(readBuffer)
                if (bytesRead <= 0) {
                    break
                }
                val floatCount = bytesRead / java.lang.Float.BYTES
                if (floatCount <= 0) {
                    continue
                }
                val byteBuffer = ByteBuffer.wrap(readBuffer, 0, floatCount * java.lang.Float.BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                val samples = FloatArray(floatCount)
                for (index in 0 until floatCount) {
                    samples[index] = byteBuffer.float
                }
                emit(samples)
            }
        }
    }.flowOn(Dispatchers.IO)
