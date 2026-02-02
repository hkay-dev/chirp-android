package dev.parakeeboard.app.feature.obsidian

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.parakeeboard.app.data.entity.Recording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages exporting recordings to Obsidian vaults via SAF (Storage Access Framework).
 */
@Singleton
class ObsidianManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

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

            // Check if file already exists and delete it (overwrite behavior)
            vaultDir.findFile(filename)?.delete()

            // Create new file
            val newFile = vaultDir.createFile("text/markdown", sanitizedTitle)
                ?: throw IllegalStateException("Failed to create file in vault")

            // Format the content
            val date = LocalDateTime.ofInstant(
                recording.createdAt.toInstant(),
                ZoneId.systemDefault()
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

            // Write content to file
            context.contentResolver.openOutputStream(newFile.uri)?.use { outputStream ->
                outputStream.write(content.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("Failed to open output stream")

            newFile.uri
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
            null
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
