package dev.chirpboard.app.feature.recording.service

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
        fun validateForStop(file: File): RecordingFileValidation = validate(file, requireMoov = true)

        fun validateForRecovery(file: File): RecordingFileValidation = validate(file, requireMoov = false)

        private fun validate(
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

        internal fun containsMoovAtom(file: File): Boolean {
            return runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    val buffer = ByteArray(8192)
                    var offset = 0L
                    while (offset < raf.length()) {
                        raf.seek(offset)
                        val read = raf.read(buffer)
                        if (read <= 0) break
                        if (buffer.copyOf(read).toString(Charsets.ISO_8859_1).contains("moov")) {
                            return true
                        }
                        offset += read
                    }
                    false
                }
            }.getOrDefault(false)
        }

        companion object {
            const val MIN_BYTES = 512L

            fun checkpointPathFor(audioPath: String): String = "$audioPath.checkpoint.m4a"

            fun recoveryPathFor(audioPath: String): String =
                if (audioPath.endsWith(".m4a")) {
                    audioPath.removeSuffix(".m4a") + ".recovery.m4a"
                } else {
                    "$audioPath.recovery.m4a"
                }
        }
    }
