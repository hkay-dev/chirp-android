package dev.chirpboard.app.feature.obsidian

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.export.TranscriptExportRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
         * Serializes vault writes. Auto-export on transcription completion and a user-triggered
         * re-export can target the same note at once; their temp documents share the
         * `$filename.tmp.` prefix, so each one's leftover sweep would delete the other's
         * in-flight file. Exports are infrequent and IO-bound, so queueing them costs nothing.
         */
        private val writeMutex = Mutex()

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
                    writeMutex.withLock { writeAtomically(vaultDir, filename, content) }
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
         * Persist the read/write grant for a freshly picked vault folder. Returns false when
         * the provider refuses (grant not persistable, or the per-app persisted-grant cap is
         * hit) so callers can surface the failure instead of storing a vault that can't be
         * reopened later.
         *
         * Suspends on the IO dispatcher: this is a Binder call into the storage provider.
         */
        suspend fun takeVaultPermission(vaultUri: Uri): Boolean =
            withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        vaultUri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    true
                } catch (e: SecurityException) {
                    Log.w(TAG, "Could not persist permission for $vaultUri", e)
                    false
                }
            }

        /**
         * Release the persistable read/write grant taken when the vault was picked. Android
         * caps persisted URI grants per app, so grants for cleared/replaced vaults must be
         * given back instead of accumulating until new picks stop persisting.
         *
         * Suspends on the IO dispatcher for the same Binder reason as [takeVaultPermission].
         */
        suspend fun releaseVaultPermission(vaultUri: Uri) {
            withContext(Dispatchers.IO) {
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
        }

        /**
         * Write content atomically using a temp-file pattern. The previous export (if any)
         * is never deleted before its replacement fully exists: it is moved aside to a
         * backup name, restored if the replacement fails, and dropped only on success.
         *
         * Flow:
         * 1. Create a uniquely named temp document and write + sync the content into it.
         * 2. Scan the vault once: locate this file's previous export and sweep temp/backup
         *    leftovers a crashed earlier export of the same file left behind.
         * 3. Move the previous export to a backup name, promote the temp document to the
         *    final name (rename, or stream-copy fallback), then drop the backup.
         *
         * @return URI of the created file
         * @throws IOException if write fails
         */
        private fun writeAtomically(
            vaultDir: DocumentFile,
            filename: String,
            content: String,
        ): Uri {
            val uniqueSuffix = UUID.randomUUID().toString().take(8)
            val tempFilename = "$filename.tmp.$uniqueSuffix"
            var tempFile: DocumentFile? = null

            try {
                tempFile = vaultDir.createFile("text/markdown", tempFilename)
                    ?: throw ObsidianVaultAccessException("Failed to create temp file: $tempFilename")

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

                val existing = findExistingAndSweepLeftovers(vaultDir, filename, tempFile.uri)
                return replaceExisting(vaultDir, tempFile, existing, filename, uniqueSuffix)
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

        /**
         * One directory scan that both locates this file's previous export and deletes
         * temp/backup leftovers from earlier crashed exports of the same file. Leftovers
         * are matched by the `$filename.tmp.` / `$filename.bak.` prefixes, so in-flight
         * exports of other recordings are never touched.
         *
         * The in-flight temp document is skipped by uri, not by the name that was requested:
         * SAF providers are free to alter the display name they assign (ExternalStorageProvider
         * appends the mime extension, and collisions get a " (1)" suffix), and a name-based
         * skip would then fall through to the prefix branch and delete the file this export
         * just wrote.
         */
        private fun findExistingAndSweepLeftovers(
            vaultDir: DocumentFile,
            filename: String,
            currentTempUri: Uri,
        ): DocumentFile? {
            var existing: DocumentFile? = null
            for (child in vaultDir.listFiles()) {
                if (child.uri == currentTempUri) continue
                val name = child.name ?: continue
                when {
                    name == filename -> existing = child
                    name.startsWith("$filename.tmp.") || name.startsWith("$filename.bak.") ->
                        deleteQuietly(child, "stale export leftover $name")
                }
            }
            return existing
        }

        /**
         * Put the durable temp content in place of the previous export. The previous note
         * is moved to a backup name first and renamed back if promotion fails, so no
         * failure path can leave the vault without either the old or the new note.
         */
        private fun replaceExisting(
            vaultDir: DocumentFile,
            tempFile: DocumentFile,
            existing: DocumentFile?,
            filename: String,
            uniqueSuffix: String,
        ): Uri {
            if (existing == null) return promoteTempFile(vaultDir, tempFile, filename)

            if (!existing.renameTo("$filename.bak.$uniqueSuffix")) {
                // Provider can't rename the old note aside; last resort is overwriting it
                // in place with the already-durable temp content.
                copyDocument(from = tempFile.uri, to = existing.uri)
                deleteQuietly(tempFile, "temp file")
                return existing.uri
            }
            try {
                val finalUri = promoteTempFile(vaultDir, tempFile, filename)
                // renameTo() repointed `existing` at the backup name.
                deleteQuietly(existing, "backup of previous export")
                return finalUri
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                try {
                    existing.renameTo(filename)
                } catch (restoreError: Exception) {
                    Log.w(TAG, "Failed to restore previous export after failed write", restoreError)
                }
                throw e
            }
        }

        /**
         * Give the fully written temp document the final name: rename when the provider
         * supports it, otherwise copy into a fresh final document. A half-written final
         * document from a failed copy is removed, never left in the vault.
         */
        private fun promoteTempFile(
            vaultDir: DocumentFile,
            tempFile: DocumentFile,
            filename: String,
        ): Uri {
            if (tempFile.renameTo(filename)) return tempFile.uri

            val finalFile =
                vaultDir.createFile("text/markdown", filename)
                    ?: throw ObsidianVaultAccessException("Failed to create final file: $filename")
            try {
                copyDocument(from = tempFile.uri, to = finalFile.uri)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                deleteQuietly(finalFile, "half-written final file")
                throw e
            }
            deleteQuietly(tempFile, "temp file")
            return finalFile.uri
        }

        private fun copyDocument(
            from: Uri,
            to: Uri,
        ) {
            // "wt" truncates: without it a shorter rewrite leaves the old note's tail bytes.
            val outStream =
                context.contentResolver.openOutputStream(to, "wt")
                    ?: throw IOException("Failed to open output stream")
            outStream.use { out ->
                val inpStream =
                    context.contentResolver.openInputStream(from)
                        ?: throw IOException("Failed to open temp stream")
                inpStream.use { inp ->
                    inp.copyTo(out)
                }
            }
        }

        private fun deleteQuietly(
            file: DocumentFile,
            label: String,
        ) {
            try {
                file.delete()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Failed to delete $label", e)
            }
        }
    }

/**
 * The vault directory refused to create a document, which in practice means the SAF grant
 * was revoked or the folder was moved/deleted. Typed so export-failure reporting can tell
 * the user to re-select the vault instead of showing a generic write error.
 */
class ObsidianVaultAccessException(
    message: String,
) : IOException(message)

private val InvalidExportFilenameCharacters = Regex("[\\\\/:*?\"<>|]")
private val ExportMultiWhitespace = Regex("\\s+")

// Runs after whitespace collapsing, so this only sees the non-whitespace control
// characters (NUL, BEL, DEL, …) that providers reject or render as tofu.
private val ExportControlCharacters = Regex("\\p{Cntrl}")

/** Filename length cap in UTF-16 units. */
private const val MaxExportFilenameLength = 100
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
        .replace(ExportControlCharacters, "")
        .trim()
        .let(::truncateAtCharacterBoundary)
        .ifBlank { "Untitled" }

/**
 * Caps the name at [MaxExportFilenameLength] UTF-16 units without splitting a surrogate
 * pair: a truncation landing mid-emoji would leave a lone surrogate, which is not valid
 * UTF-8 and gets rejected or mangled by SAF providers.
 */
private fun truncateAtCharacterBoundary(name: String): String {
    if (name.length <= MaxExportFilenameLength) return name
    val cut = name.take(MaxExportFilenameLength)
    return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}
