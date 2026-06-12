package dev.chirpboard.app.feature.recording.session.validation

import dev.chirpboard.app.core.audio.RecordingOutputFormat
import dev.chirpboard.app.core.audio.WavFileWriter
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

enum class RecordingValidationLevel {
    PLAYABLE,
    RECOVERABLE_STUB,
    INVALID,
}

data class RecordingFileValidation(
    val level: RecordingValidationLevel,
    val failureReason: String? = null,
) {
    val isPlayable: Boolean get() = level == RecordingValidationLevel.PLAYABLE

    val isRecoverableStub: Boolean get() = level == RecordingValidationLevel.RECOVERABLE_STUB
}

@Singleton
class RecordingFileValidator
    @Inject
    constructor() {
        fun validateForStop(file: File): RecordingFileValidation =
            when (RecordingOutputFormat.fromFile(file)) {
                RecordingOutputFormat.M4A -> validateM4a(file, requireMoov = true)
                RecordingOutputFormat.WAV -> validateWav(file)
                RecordingOutputFormat.MP3 -> validateMp3(file)
            }

        fun validateForRecovery(file: File): RecordingFileValidation =
            when (RecordingOutputFormat.fromFile(file)) {
                RecordingOutputFormat.M4A -> validateM4a(file, requireMoov = false)
                RecordingOutputFormat.WAV -> validateWav(file, allowRecoverableStub = true)
                RecordingOutputFormat.MP3 -> validateMp3(file, allowRecoverableStub = true)
            }

        private fun validateM4a(
            file: File,
            requireMoov: Boolean,
        ): RecordingFileValidation {
            if (!file.exists()) {
                return RecordingFileValidation(RecordingValidationLevel.INVALID, "Audio file does not exist")
            }
            if (file.length() < MIN_BYTES) {
                return RecordingFileValidation(RecordingValidationLevel.INVALID, "Audio file is too small")
            }
            if (!hasMp4ContainerHeader(file)) {
                return RecordingFileValidation(RecordingValidationLevel.INVALID, "Audio file is not a valid M4A container")
            }
            if (requireMoov && !containsMoovAtom(file)) {
                return RecordingFileValidation(
                    RecordingValidationLevel.RECOVERABLE_STUB,
                    "Recording file is incomplete (missing moov atom)",
                )
            }
            return RecordingFileValidation(
                if (requireMoov) RecordingValidationLevel.PLAYABLE else RecordingValidationLevel.RECOVERABLE_STUB,
            )
        }

        private fun validateWav(
            file: File,
            allowRecoverableStub: Boolean = false,
        ): RecordingFileValidation {
            if (!file.exists()) {
                return RecordingFileValidation(RecordingValidationLevel.INVALID, "Audio file does not exist")
            }
            if (file.length() < WavFileWriter.WAV_HEADER_BYTES + MIN_BYTES) {
                return RecordingFileValidation(RecordingValidationLevel.INVALID, "Audio file is too small")
            }
            if (!WavFileWriter.hasAccurateHeader(file)) {
                // A crash can leave a zeroed or stale-size header on top of a plausible PCM
                // payload. In recovery mode that payload is repairable, so surface it as a
                // recoverable stub instead of discarding it; recovery repairs the header
                // before treating the file as playable.
                return if (allowRecoverableStub) {
                    RecordingFileValidation(
                        RecordingValidationLevel.RECOVERABLE_STUB,
                        "WAV header is incomplete but PCM payload is recoverable",
                    )
                } else {
                    RecordingFileValidation(RecordingValidationLevel.INVALID, "Audio file is not a valid WAV container")
                }
            }
            return RecordingFileValidation(
                if (allowRecoverableStub) RecordingValidationLevel.RECOVERABLE_STUB else RecordingValidationLevel.PLAYABLE,
            )
        }

        private fun validateMp3(
            file: File,
            allowRecoverableStub: Boolean = false,
        ): RecordingFileValidation {
            if (!file.exists()) {
                return RecordingFileValidation(RecordingValidationLevel.INVALID, "Audio file does not exist")
            }
            if (file.length() < MIN_BYTES) {
                return RecordingFileValidation(RecordingValidationLevel.INVALID, "Audio file is too small")
            }
            if (!hasMp3FrameSync(file)) {
                return RecordingFileValidation(RecordingValidationLevel.INVALID, "Audio file is not a valid MP3 stream")
            }
            return RecordingFileValidation(
                if (allowRecoverableStub) RecordingValidationLevel.RECOVERABLE_STUB else RecordingValidationLevel.PLAYABLE,
            )
        }

        private fun hasMp4ContainerHeader(file: File): Boolean {
            return runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    if (raf.length() < 8) return false
                    val type = ByteArray(4)
                    raf.seek(4)
                    raf.readFully(type)
                    String(type, Charsets.US_ASCII) == "ftyp"
                }
            }.getOrDefault(false)
        }

        /**
         * Whether the MP4/M4A file contains the `moov` atom signature. Streams the file
         * in fixed buffers and matches the 4-byte `moov` marker directly against the raw
         * bytes, retaining a 3-byte overlap window between buffers so a marker straddling
         * a buffer boundary is still found (the previous per-chunk substring scan missed
         * those and allocated a ~16KB String per 8KB read). Detection semantics are
         * otherwise unchanged: the marker is matched wherever it appears in the file.
         */
        internal fun containsMoovAtom(file: File): Boolean {
            return runCatching {
                file.inputStream().use { input ->
                    val window = ByteArray(MOOV_SCAN_BUFFER_BYTES)
                    // Carry the last (MOOV_MARKER.size - 1) bytes of the previous buffer
                    // into the front of the next so a marker split across the boundary is
                    // not lost.
                    var carry = 0
                    while (true) {
                        val read = input.read(window, carry, window.size - carry)
                        if (read <= 0) break
                        val filled = carry + read
                        if (indexOfMarker(window, filled, MOOV_MARKER) >= 0) {
                            return true
                        }
                        carry = minOf(MOOV_MARKER.size - 1, filled)
                        if (carry > 0) {
                            System.arraycopy(window, filled - carry, window, 0, carry)
                        }
                    }
                    false
                }
            }.getOrDefault(false)
        }

        private fun indexOfMarker(
            buffer: ByteArray,
            length: Int,
            marker: ByteArray,
        ): Int {
            if (length < marker.size) return -1
            outer@ for (start in 0..length - marker.size) {
                for (i in marker.indices) {
                    if (buffer[start + i] != marker[i]) continue@outer
                }
                return start
            }
            return -1
        }

        private fun hasMp3FrameSync(file: File): Boolean {
            return runCatching {
                file.inputStream().use { input ->
                    val header = ByteArray(3)
                    val read = input.read(header)
                    if (read < 2) return false
                    if (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
                        return true
                    }
                    header[0].toInt() and 0xFF == 0xFF && (header[1].toInt() and 0xE0) == 0xE0
                }
            }.getOrDefault(false)
        }

        companion object {
            const val MIN_BYTES = 512L
            private const val MOOV_SCAN_BUFFER_BYTES = 8192
            private val MOOV_MARKER = "moov".toByteArray(Charsets.US_ASCII)

            fun checkpointPathFor(audioPath: String): String {
                val format = RecordingOutputFormat.fromExtension(File(audioPath).extension)
                return "$audioPath.checkpoint${format.fileExtension}"
            }

            fun recoveryPathFor(audioPath: String): String {
                val format = RecordingOutputFormat.fromExtension(File(audioPath).extension)
                return if (audioPath.endsWith(format.fileExtension)) {
                    audioPath.removeSuffix(format.fileExtension) + ".recovery${format.fileExtension}"
                } else {
                    "$audioPath.recovery${format.fileExtension}"
                }
            }
        }
    }
