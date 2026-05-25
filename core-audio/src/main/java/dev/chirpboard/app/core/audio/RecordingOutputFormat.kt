package dev.chirpboard.app.core.audio

import java.io.File
import java.util.Locale

enum class RecordingOutputFormat(
    val storageValue: String,
    val fileExtension: String,
    val displayLabel: String,
    val mimeType: String,
) {
    M4A(
        storageValue = "m4a",
        fileExtension = ".m4a",
        displayLabel = "M4A (AAC)",
        mimeType = "audio/mp4",
    ),
    MP3(
        storageValue = "mp3",
        fileExtension = ".mp3",
        displayLabel = "MP3",
        mimeType = "audio/mpeg",
    ),
    WAV(
        storageValue = "wav",
        fileExtension = ".wav",
        displayLabel = "WAV (PCM)",
        mimeType = "audio/wav",
    ),
    ;

    fun fileName(prefix: String): String = "$prefix$fileExtension"

    companion object {
        val DEFAULT = M4A

        fun fromStorageValue(value: String?): RecordingOutputFormat =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT

        fun fromFile(file: File): RecordingOutputFormat = fromExtension(file.extension)

        fun fromExtension(extension: String): RecordingOutputFormat {
            val normalized = extension.lowercase(Locale.US).let { ext ->
                if (ext.startsWith(".")) ext else ".$ext"
            }
            return entries.firstOrNull { it.fileExtension == normalized } ?: DEFAULT
        }
    }
}
