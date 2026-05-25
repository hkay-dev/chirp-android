package dev.chirpboard.app.feature.recording.service

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import dev.chirpboard.app.core.audio.RecordingOutputFormat
import dev.chirpboard.app.core.audio.WavFileWriter
import dev.chirpboard.app.feature.recording.util.probeDurationUs
import dev.chirpboard.app.feature.recording.session.validation.RecordingFileValidator
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

sealed class SegmentConcatResult {
    data object Success : SegmentConcatResult()

    data class Failed(val reason: String) : SegmentConcatResult()
}

@Singleton
class RecordingSegmentConcatenator
    @Inject
    constructor() {
        fun concatToExport(
            segments: List<File>,
            outputFile: File,
        ): SegmentConcatResult {
            val existingSegments = segments.filter { it.exists() && it.length() >= RecordingFileValidator.MIN_BYTES }
            if (existingSegments.isEmpty()) {
                return SegmentConcatResult.Failed("No segment files to merge")
            }

            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) {
                outputFile.delete()
            }

            if (existingSegments.size == 1) {
                return runCatching {
                    existingSegments.first().copyTo(outputFile, overwrite = true)
                    if (outputFile.length() < RecordingFileValidator.MIN_BYTES) {
                        SegmentConcatResult.Failed("Merged file is too small")
                    } else {
                        SegmentConcatResult.Success
                    }
                }.getOrElse { error ->
                    SegmentConcatResult.Failed("Failed to copy segment: ${error.message}")
                }
            }

            return when (RecordingOutputFormat.fromFile(outputFile)) {
                RecordingOutputFormat.M4A -> muxAacSegments(existingSegments, outputFile)
                RecordingOutputFormat.WAV -> concatWavSegments(existingSegments, outputFile)
                RecordingOutputFormat.MP3 -> concatMp3Segments(existingSegments, outputFile)
            }
        }

        private fun muxAacSegments(
            segments: List<File>,
            outputFile: File,
        ): SegmentConcatResult {
            var muxer: MediaMuxer? = null
            var muxerTrackIndex = -1
            var presentationOffsetUs = 0L

            return runCatching {
                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                for (segment in segments) {
                    val extractor = MediaExtractor()
                    extractor.setDataSource(segment.absolutePath)
                    val trackIndex = selectAudioTrack(extractor)
                    if (trackIndex < 0) {
                        extractor.release()
                        continue
                    }
                    extractor.selectTrack(trackIndex)

                    if (muxerTrackIndex < 0) {
                        muxerTrackIndex = muxer!!.addTrack(extractor.getTrackFormat(trackIndex))
                        muxer!!.start()
                    }

                    val buffer = ByteBuffer.allocate(BUFFER_SIZE)
                    val bufferInfo = MediaCodec.BufferInfo()

                    while (true) {
                        bufferInfo.size = extractor.readSampleData(buffer, 0)
                        if (bufferInfo.size < 0) break
                        bufferInfo.offset = 0
                        bufferInfo.presentationTimeUs = presentationOffsetUs + extractor.sampleTime
                        @Suppress("WrongConstant")
                        bufferInfo.flags = extractor.sampleFlags
                        muxer!!.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                        if (!extractor.advance()) break
                    }

                    presentationOffsetUs += probeDurationUs(segment)
                    extractor.release()
                }

                muxer!!.stop()
                if (outputFile.exists() && outputFile.length() >= RecordingFileValidator.MIN_BYTES) {
                    SegmentConcatResult.Success
                } else {
                    SegmentConcatResult.Failed("Merged export file is invalid")
                }
            }.getOrElse { error ->
                if (outputFile.exists()) outputFile.delete()
                Log.e(TAG, "Segment concat failed", error)
                SegmentConcatResult.Failed(error.message ?: "Segment concat failed")
            }.also {
                runCatching { muxer?.release() }
            }
        }

        private fun concatWavSegments(
            segments: List<File>,
            outputFile: File,
        ): SegmentConcatResult {
            return runCatching {
                val firstFormat = readWavSampleRate(segments.first())
                val writer = WavFileWriter(outputFile, firstFormat)
                val pcmBuffer = ByteArray(BUFFER_SIZE)
                segments.forEach { segment ->
                    segment.inputStream().use { input ->
                        input.skip(WavFileWriter.WAV_HEADER_BYTES.toLong())
                        while (true) {
                            val read = input.read(pcmBuffer)
                            if (read <= 0) break
                            writer.appendPcm16(pcmBuffer, read)
                        }
                    }
                }
                writer.close()
                if (outputFile.length() >= RecordingFileValidator.MIN_BYTES) {
                    SegmentConcatResult.Success
                } else {
                    SegmentConcatResult.Failed("Merged WAV file is invalid")
                }
            }.getOrElse { error ->
                if (outputFile.exists()) outputFile.delete()
                SegmentConcatResult.Failed(error.message ?: "WAV concat failed")
            }
        }

        private fun concatMp3Segments(
            segments: List<File>,
            outputFile: File,
        ): SegmentConcatResult {
            return runCatching {
                FileOutputStream(outputFile).use { output ->
                    segments.forEach { segment ->
                        segment.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                }
                if (outputFile.length() >= RecordingFileValidator.MIN_BYTES) {
                    SegmentConcatResult.Success
                } else {
                    SegmentConcatResult.Failed("Merged MP3 file is invalid")
                }
            }.getOrElse { error ->
                if (outputFile.exists()) outputFile.delete()
                SegmentConcatResult.Failed(error.message ?: "MP3 concat failed")
            }
        }

        private fun readWavSampleRate(file: File): Int {
            file.inputStream().use { input ->
                input.skip(24)
                val rateBytes = ByteArray(4)
                input.read(rateBytes)
                return (rateBytes[0].toInt() and 0xFF) or
                    ((rateBytes[1].toInt() and 0xFF) shl 8) or
                    ((rateBytes[2].toInt() and 0xFF) shl 16) or
                    ((rateBytes[3].toInt() and 0xFF) shl 24)
            }
        }

        private fun selectAudioTrack(extractor: MediaExtractor): Int {
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) return index
            }
            return -1
        }

        companion object {
            private const val TAG = "RecordingSegmentConcatenator"
            private const val BUFFER_SIZE = 256 * 1024
        }
    }
