package dev.chirpboard.app.feature.obsidian

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.data.entity.Recording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException
import java.io.SyncFailedException
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages exporting recordings to Obsidian vaults via SAF (Storage Access Framework).
 */
@Singleton
class ObsidianManager @Inject constructor(
    @ApplicationContext private val context: Context
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
        recording: Recording,
        transcript: String,
        summary: String?,
        vaultUri: Uri,
        tags: List<String> = emptyList()
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val vaultDir = DocumentFile.fromTreeUri(context, vaultUri)
                ?: throw IllegalArgumentException("Cannot access vault directory")

            if (!vaultDir.canWrite()) {
                throw SecurityException("No write permission for vault directory")
            }

            // Generate filename from title (sanitized)
            val sanitizedTitle = sanitizeFilename(recording.title)
            val filename = "$sanitizedTitle.md"

            // Format the content
            val date = LocalDateTime.ofInstant(
                recording.createdAt.toInstant(),
                java.time.ZoneOffset.UTC
            )
            val durationSeconds = recording.durationMs / 1000

            val content = MarkdownFormatter.format(
                title = recording.title,
                transcript = transcript,
                summary = summary,
                date = date,
                durationSeconds = durationSeconds,
                tags = tags,
                source = recording.source.name.lowercase()
            )

            // Write atomically to prevent data loss on crash
            writeAtomically(vaultDir, filename, content)
        }
    }

    /**
     * Check if we have SAF permission for the given vault URI.
     *
     * @param vaultUri The SAF URI to check
     * @return true if we have read/write access
     */
    fun hasVaultAccess(vaultUri: Uri): Boolean {
        return try {
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
     * @param vaultUri The SAF URI
     * @return Display name or null if unavailable
     */
    fun getVaultDisplayName(vaultUri: Uri): String? {
        return try {
            DocumentFile.fromTreeUri(context, vaultUri)?.name
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
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
        content: String
    ): Uri {
        val tempFilename = "${filename}.tmp.${UUID.randomUUID().toString().take(8)}"
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

            // Step 3: Delete existing file if present
            vaultDir.findFile(filename)?.delete()

            // Step 4: Rename temp to final
            // Note: SAF renameTo() can be unreliable, handle fallback
            if (!tempFile.renameTo(filename)) {
                // Fallback: create final file, copy content, delete temp
                val finalFile = vaultDir.createFile("text/markdown", filename)
                    ?: throw IOException("Failed to create final file: $filename")

                val outStream = context.contentResolver.openOutputStream(finalFile.uri)
                    ?: throw IOException("Failed to open output stream")
                outStream.use { out ->
                    val inpStream = context.contentResolver.openInputStream(tempFile.uri)
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

    /**
     * Sanitize a string for use as a filename.
     * Removes or replaces characters that are invalid in filenames.
     */
    private fun sanitizeFilename(name: String): String {
        return name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(100) // Limit length
            .ifBlank { "Untitled" }
    }
}
