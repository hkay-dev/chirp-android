package dev.chirpboard.app.feature.recording.importing

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.feature.recording.R
import dev.chirpboard.app.feature.recording.util.probeDurationMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AudioImportResult {
    data class SavedAndQueued(
        val recordingId: UUID,
    ) : AudioImportResult

    data class SavedPendingRecovery(
        val recordingId: UUID,
        val message: String,
        val cause: Throwable? = null,
    ) : AudioImportResult

    data class FailedBeforePersistence(
        val message: String,
        val cause: Throwable? = null,
    ) : AudioImportResult
}

@Singleton
class AudioImportOrchestrator
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val recordingRepository: RecordingRepository,
        private val transcriptionRecovery: TranscriptionRecovery,
    ) {
        suspend fun import(uri: Uri): AudioImportResult =
            withContext(Dispatchers.IO) {
                val outputDir = File(context.filesDir, "recordings").apply { mkdirs() }
                val outputFile =
                    File(
                        outputDir,
                        "imported_${System.currentTimeMillis()}.${resolveExtension(uri)}",
                    )

                val copiedFile = copyIntoAppStorage(uri, outputFile)
                if (copiedFile is AudioImportResult.FailedBeforePersistence) {
                    return@withContext copiedFile
                }

                val durationMs = probeDurationMs(outputFile)
                val recording =
                    try {
                        recordingRepository.createRecording(
                            title = context.getString(R.string.rec_import_default_title),
                            audioPath = outputFile.absolutePath,
                            source = RecordingSource.IMPORTED,
                            durationMs = durationMs,
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        deleteQuietly(outputFile)
                        return@withContext AudioImportResult.FailedBeforePersistence(
                            message = context.getString(R.string.rec_import_save_failed),
                            cause = e,
                        )
                    }

                try {
                    transcriptionRecovery.enqueue(recording.id, UUID.randomUUID().toString())
                    AudioImportResult.SavedAndQueued(recording.id)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e

                    val reason = context.getString(R.string.rec_import_queue_recovery)
                    runCatching {
                        transcriptionRecovery.markPendingForQueueRecovery(recording.id, reason, e)
                    }

                    AudioImportResult.SavedPendingRecovery(
                        recordingId = recording.id,
                        message = reason,
                        cause = e,
                    )
                }
            }

        private fun copyIntoAppStorage(
            uri: Uri,
            outputFile: File,
        ): AudioImportResult? =
            try {
                val inputStream =
                    context.contentResolver.openInputStream(uri)
                        ?: return AudioImportResult.FailedBeforePersistence(
                            message = context.getString(R.string.rec_import_open_failed),
                        )

                inputStream.use { input ->
                    outputFile.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                }

                null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                deleteQuietly(outputFile)
                AudioImportResult.FailedBeforePersistence(
                    message = context.getString(R.string.rec_import_copy_failed),
                    cause = e,
                )
            }

        private fun resolveExtension(uri: Uri): String {
            val mimeType = context.contentResolver.getType(uri)
            val fromMime = mimeType?.substringAfter('/', missingDelimiterValue = "")?.substringBefore(';')
            val fromUri = uri.lastPathSegment?.substringAfterLast('.', missingDelimiterValue = "")

            return sanitizeImportedAudioExtension(fromMime)
                ?: sanitizeImportedAudioExtension(fromUri)
                ?: DEFAULT_IMPORT_EXTENSION
        }

        private fun deleteQuietly(file: File) {
            runCatching {
                if (file.exists()) {
                    file.delete()
                }
            }
        }

        private companion object {
            const val DEFAULT_IMPORT_EXTENSION = "m4a"
        }
    }

private const val MAX_IMPORT_EXTENSION_LENGTH = 5
private val IMPORT_EXTENSION_DISALLOWED_CHARS = Regex("[^A-Za-z0-9]")

/**
 * Sanitizes an extension candidate derived from an untrusted shared URI or MIME type.
 *
 * The raw last path segment of a content URI can contain encoded separators
 * (`%2F`, `..`), which would redirect the imported file out of the recordings/
 * directory where cleanup and recovery expect it (SEC-9). Stripping everything
 * outside `[A-Za-z0-9]` removes path separators and dots entirely; the result is
 * lowercased and length-capped, or null when nothing safe remains.
 */
internal fun sanitizeImportedAudioExtension(candidate: String?): String? =
    candidate
        ?.replace(IMPORT_EXTENSION_DISALLOWED_CHARS, "")
        ?.take(MAX_IMPORT_EXTENSION_LENGTH)
        ?.lowercase()
        ?.takeIf(String::isNotEmpty)
