package dev.chirpboard.app.feature.obsidian

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.export.TranscriptExportRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.io.SyncFailedException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages exporting recordings to Obsidian vaults via SAF (Storage Access Framework).
 */
@Singleton
class ObsidianManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            private const val TAG = "ObsidianManager"
        }

        /**
         * Export recording transcript to Obsidian vault as Markdown.
         *
         * @param recording The recording entity
         * @param transcript The transcript text
         * @param summary Optional summary text
         * @param vaultUri SAF URI to the vault folder
         * @param tags List of tag names associated with the recording
         * @return Result with the exported file URI or error
         */
        suspend fun export(
            recording: TranscriptExportRecording,
            transcript: String,
            summary: String?,
            vaultUri: Uri,
            tags: List<String> = emptyList(),
        ): Result<Uri> =
            withContext(Dispatchers.IO) {
                runCatching {
                    val vaultDir =
                        DocumentFile.fromTreeUri(context, vaultUri)
                            ?: throw IllegalArgumentException("Cannot access vault directory")

                    if (!vaultDir.canWrite()) {
                        throw SecurityException("No write permission for vault directory")
                    }

                    // Deterministic per-recording filename: the created-at suffix keeps two
                    // same-titled recordings from mapping to the same note, so an export can
                    // only ever overwrite this recording's own earlier export — never an
                    // unrelated vault note.
                    val filename =
                        buildObsidianExportFilename(
                            title = recording.title,
                            createdAtEpochMs = recording.createdAtEpochMs,
                            zoneId = ZoneId.systemDefault(),
                        )

                    // Format the content using the user's local wall-clock time so daily-note
                    // linking and frontmatter dates match what the rest of the app shows.
                    val date =
                        LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(recording.createdAtEpochMs),
                            ZoneId.systemDefault(),
                        )
                    val durationSeconds = recording.durationMs / 1000

                    val content =
                        MarkdownFormatter.format(
                            title = recording.title,
                            transcript = transcript,
                            summary = summary,
                            date = date,
                            durationSeconds = durationSeconds,
                            tags = tags,
                            source = recording.sourceName.lowercase(),
                        )

                    // Write atomically to prevent data loss on crash
                    writeAtomically(vaultDir, filename, content)
                }
            }

        /**
         * Check if we have SAF permission for the given vault URI.
         *
         * Suspends on the IO dispatcher: DocumentFile access goes over Binder to the storage
         * provider, which can stall on slow or cloud-backed trees.
         *
         * @param vaultUri The SAF URI to check
         * @return true if we have read/write access
         */
        suspend fun hasVaultAccess(vaultUri: Uri): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    val vaultDir = DocumentFile.fromTreeUri(context, vaultUri)
                    vaultDir?.canRead() == true && vaultDir.canWrite()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    false
                }
            }

        /**
         * Get the display name for a vault URI.
         *
         * Suspends on the IO dispatcher for the same Binder-stall reason as [hasVaultAccess].
         *
         * @param vaultUri The SAF URI
         * @return Display name or null if unavailable
         */
        suspend fun getVaultDisplayName(vaultUri: Uri): String? =
            withContext(Dispatchers.IO) {
                try {
                    DocumentFile.fromTreeUri(context, vaultUri)?.name
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null
                }
            }

        /**
         * Release the persistable read/write grant taken when the vault was picked. Android
         * caps persisted URI grants per app, so grants for cleared/replaced vaults must be
         * given back instead of accumulating until new picks stop persisting.
         */
        fun releaseVaultPermission(vaultUri: Uri) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    vaultUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                // Not held (already revoked externally) — nothing to release.
                Log.w(TAG, "No persistable permission to release for $vaultUri", e)
            }
        }

        /**
         * Write content atomically using temp file pattern.
         *
         * Flow:
         * 1. Create temp file with UUID suffix
         * 2. Write content with flush and sync
         * 3. Delete existing file (if any)
         * 4. Rename temp to final name
         * 5. Clean up temp on any failure
         *
         * @return URI of the created file
         * @throws IOException if write fails
         */
        private fun writeAtomically(
            vaultDir: DocumentFile,
            filename: String,
            content: String,
        ): Uri {
            val tempFilename = "$filename.tmp.${UUID.randomUUID().toString().take(8)}"
            var tempFile: DocumentFile? = null

            try {
                // Step 1: Create temp file
                tempFile = vaultDir.createFile("text/markdown", tempFilename)
                    ?: throw IOException("Failed to create temp file: $tempFilename")

                // Step 2: Write with sync
                context.contentResolver.openFileDescriptor(tempFile.uri, "w")?.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { fos ->
                        fos.write(content.toByteArray(Charsets.UTF_8))
                        fos.flush()
                        try {
                            pfd.fileDescriptor.sync()
                        } catch (e: SyncFailedException) {
                            // Some SAF providers don't support sync - log but continue
                            Log.w(TAG, "Sync not supported by provider", e)
                        }
                    }
                } ?: throw IOException("Failed to open temp file for writing")

                // Step 3: Delete existing file if present. The filename embeds the
                // recording's created-at timestamp, so a match here is this recording's
                // own previous export being refreshed — not another note.
                vaultDir.findFile(filename)?.delete()

                // Step 4: Rename temp to final
                // Note: SAF renameTo() can be unreliable, handle fallback
                if (!tempFile.renameTo(filename)) {
                    // Fallback: create final file, copy content, delete temp
                    val finalFile =
                        vaultDir.createFile("text/markdown", filename)
                            ?: throw IOException("Failed to create final file: $filename")

                    val outStream =
                        context.contentResolver.openOutputStream(finalFile.uri)
                            ?: throw IOException("Failed to open output stream")
                    outStream.use { out ->
                        val inpStream =
                            context.contentResolver.openInputStream(tempFile.uri)
                                ?: throw IOException("Failed to open temp stream")
                        inpStream.use { inp ->
                            inp.copyTo(out)
                        }
                    }
                    tempFile.delete()
                    return finalFile.uri
                }

                return tempFile.uri
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Clean up temp file on any failure
                try {
                    tempFile?.delete()
                } catch (cleanupError: Exception) {
                    Log.w(TAG, "Failed to clean up temp file", cleanupError)
                }
                throw e
            }
        }

    }

private val InvalidExportFilenameCharacters = Regex("[\\\\/:*?\"<>|]")
private val ExportMultiWhitespace = Regex("\\s+")
private val ExportFilenameTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss")

/**
 * Builds the deterministic export filename for a recording: sanitized title plus the
 * recording's local created-at timestamp. Same recording (same title) always maps to the
 * same file (re-export updates in place); different recordings sharing a title get
 * distinct names, so exports never destroy unrelated notes.
 */
internal fun buildObsidianExportFilename(
    title: String,
    createdAtEpochMs: Long,
    zoneId: ZoneId,
): String {
    val timestamp =
        LocalDateTime
            .ofInstant(Instant.ofEpochMilli(createdAtEpochMs), zoneId)
            .format(ExportFilenameTimestampFormatter)
    return "${sanitizeObsidianFilename(title)} ($timestamp).md"
}

/**
 * Sanitize a string for use as a filename.
 * Removes or replaces characters that are invalid in filenames.
 */
internal fun sanitizeObsidianFilename(name: String): String =
    name
        .replace(InvalidExportFilenameCharacters, "_")
        .replace(ExportMultiWhitespace, " ")
        .trim()
        .take(100) // Limit length
        .ifBlank { "Untitled" }
