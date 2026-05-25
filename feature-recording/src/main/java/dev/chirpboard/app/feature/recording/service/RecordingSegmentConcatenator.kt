package dev.chirpboard.app.feature.recording.service

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import dev.chirpboard.app.feature.recording.util.useCompat
import dev.chirpboard.app.feature.recording.session.validation.RecordingFileValidator
import java.io.File
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

            return runCatching {
                muxSegments(existingSegments, outputFile)
            }.getOrElse { error ->
                Log.e(TAG, "Segment concat failed", error)
                SegmentConcatResult.Failed(error.message ?: "Segment concat failed")
            }
        }

        private fun muxSegments(
            segments: List<File>,
            outputFile: File,
        ): SegmentConcatResult {
            var muxer: MediaMuxer? = null
            var muxerTrackIndex = -1
            var presentationOffsetUs = 0L

            try {
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
                        muxerTrackIndex = muxer.addTrack(extractor.getTrackFormat(trackIndex))
                        muxer.start()
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
                        muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                        if (!extractor.advance()) break
                    }

                    presentationOffsetUs += probeDurationUs(segment)
                    extractor.release()
                }

                muxer.stop()
            } catch (e: Exception) {
                if (outputFile.exists()) outputFile.delete()
                return SegmentConcatResult.Failed(e.message ?: "Mux failed")
            } finally {
                runCatching { muxer?.release() }
            }

            return if (outputFile.exists() && outputFile.length() >= RecordingFileValidator.MIN_BYTES) {
                SegmentConcatResult.Success
            } else {
                SegmentConcatResult.Failed("Merged export file is invalid")
            }
        }

        private fun selectAudioTrack(extractor: MediaExtractor): Int {
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) return index
            }
            return -1
        }

        private fun probeDurationUs(file: File): Long {
            val durationMs =
                runCatching {
                    MediaMetadataRetriever().useCompat { retriever ->
                        retriever.setDataSource(file.absolutePath)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    }
                }.getOrNull()?.coerceAtLeast(0L) ?: 0L
            return durationMs * 1_000L
        }

        companion object {
            private const val TAG = "RecordingSegmentConcatenator"
            private const val BUFFER_SIZE = 256 * 1024
        }
    }
