package dev.chirpboard.app.feature.keyboard.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import dev.chirpboard.app.core.audio.KeyboardRecordingQualityConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes raw PCM audio samples to M4A (AAC) format.
 */
class AudioEncoder {
    companion object {
        private const val TAG = "AudioEncoder"
        private const val MIME_TYPE = "audio/mp4a-latm"
        private const val DEFAULT_BIT_RATE = 64_000
        private const val TIMEOUT_US = 10_000L
    }

    /**
     * Encode float samples to M4A file.
     *
     * @param samples Raw audio samples in [-1.0, 1.0] range
     * @param sampleRate Sample rate in Hz (typically 16000)
     * @param outputPath Full path for output M4A file
     * @param config AAC encoding configuration
     * @return true if encoding succeeded, false otherwise
     */
    fun encodeToM4a(
        samples: FloatArray,
        sampleRate: Int,
        outputPath: String,
        config: KeyboardRecordingQualityConfig = KeyboardRecordingQualityConfig(DEFAULT_BIT_RATE),
    ): Boolean {
        if (samples.isEmpty()) {
            Log.w(TAG, "Cannot encode empty samples")
            return false
        }

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false

        try {
            File(outputPath).parentFile?.mkdirs()

            val pcmData = floatToPcm16(samples)
            val format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitRate)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, pcmData.size)
            }

            codec = MediaCodec.createEncoderByType(MIME_TYPE)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var inputOffset = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val remaining = pcmData.size - inputOffset

                        if (remaining <= 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val size = minOf(remaining, inputBuffer.capacity())
                            inputBuffer.clear()
                            inputBuffer.put(pcmData, inputOffset, size)
                            val presentationTimeUs = (inputOffset.toLong() * 1_000_000) / (sampleRate * 2)
                            codec.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
                            inputOffset += size
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)!!

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }

                        if (bufferInfo.size > 0 && muxerStarted) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }

                        codec.releaseOutputBuffer(outputIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            Log.d(TAG, "Successfully encoded to $outputPath")
            return true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Encoding failed", e)
            try {
                File(outputPath).delete()
            } catch (_: Exception) {
            }
            return false
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                if (muxerStarted) muxer?.stop()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun floatToPcm16(samples: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            val pcm = (sample * 32767).toInt().coerceIn(-32768, 32767).toShort()
            buffer.putShort(pcm)
        }
        return buffer.array()
    }
}