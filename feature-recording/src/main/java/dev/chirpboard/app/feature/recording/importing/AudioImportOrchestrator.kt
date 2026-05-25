package dev.chirpboard.app.feature.recording.importing

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.feature.recording.util.useCompat
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
class ImportedAudioMetadataReader
    @Inject
    constructor() {
        fun readDurationMs(copiedFile: File): Long =
            try {
                MediaMetadataRetriever().useCompat { retriever ->
                    retriever.setDataSource(copiedFile.absolutePath)
                    retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                0L
            }
    }

@Singleton
class AudioImportOrchestrator
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val recordingRepository: RecordingRepository,
        private val transcriptionRecovery: TranscriptionRecovery,
        private val metadataReader: ImportedAudioMetadataReader,
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

                val durationMs = metadataReader.readDurationMs(outputFile)
                val recording =
                    try {
                        recordingRepository.createRecording(
                            title = "Imported Audio",
                            audioPath = outputFile.absolutePath,
                            source = RecordingSource.IMPORTED,
                            durationMs = durationMs,
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        deleteQuietly(outputFile)
                        return@withContext AudioImportResult.FailedBeforePersistence(
                            message = "Couldn't save the imported audio.",
                            cause = e,
                        )
                    }

                try {
                    transcriptionRecovery.enqueue(recording.id, UUID.randomUUID().toString())
                    AudioImportResult.SavedAndQueued(recording.id)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e

                    val reason = "Import finished, but queue handoff failed. Recovery is ready on startup."
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
                            message = "Couldn't open the shared audio file.",
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
                    message = "Couldn't copy the shared audio file.",
                    cause = e,
                )
            }

        private fun resolveExtension(uri: Uri): String {
            val mimeType = context.contentResolver.getType(uri)
            val fromMime = mimeType?.substringAfter('/', missingDelimiterValue = "")?.substringBefore(';')
            val fromUri = uri.lastPathSegment?.substringAfterLast('.', missingDelimiterValue = "")

            return when {
                !fromMime.isNullOrBlank() -> fromMime
                !fromUri.isNullOrBlank() -> fromUri
                else -> "m4a"
            }
        }

        private fun deleteQuietly(file: File) {
            runCatching {
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

