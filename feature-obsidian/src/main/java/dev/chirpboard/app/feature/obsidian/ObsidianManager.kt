package dev.chirpboard.app.feature.obsidian

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
import java.security.MessageDigest
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
        private val exportRecords: ObsidianExportRecordStore,
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
         * @return Result with the written document and how it landed, or error
         */
        suspend fun export(
            recording: TranscriptExportRecording,
            transcript: String,
            summary: String?,
            vaultUri: Uri,
            tags: List<String> = emptyList(),
        ): Result<ObsidianExportResult> =
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
                    val baseFilename =
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

                    // Write atomically to prevent data loss on crash. The bookkeeping read and
                    // write live inside the lock so two exports of the same note can't both
                    // decide against the same stale record.
                    writeMutex.withLock {
                        val lastExport = readLastExport(baseFilename)
                        val result = writeAtomically(vaultDir, baseFilename, lastExport, content)
                        recordExport(
                            baseFilename = baseFilename,
                            record =
                                ObsidianExportRecord(
                                    filename = result.filename,
                                    contentHash = sha256Hex(content),
                                ),
                        )
                        result
                    }
                }
            }

        /**
         * Bookkeeping must never fail an export that already reached the vault. A missing
         * record only makes the next export treat the note as edited, which costs a conflict
         * copy instead of losing content.
         */
        private suspend fun readLastExport(baseFilename: String): ObsidianExportRecord? =
            try {
                exportRecords.lastExport(baseFilename)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not read export bookkeeping for $baseFilename", e)
                null
            }

        private suspend fun recordExport(
            baseFilename: String,
            record: ObsidianExportRecord,
        ) {
            try {
                exportRecords.recordExport(baseFilename, record)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not store export bookkeeping for $baseFilename", e)
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
         * 2. Scan the vault once: locate this note's previous export and sweep temp/backup
         *    leftovers a crashed earlier export of the same file left behind.
         * 3. Decide whether that previous export is still exactly what this app wrote. If it
         *    is not, the user edited it in Obsidian and it is left alone — the new content
         *    goes to a conflict copy instead.
         * 4. Otherwise move the previous export to a backup name, promote the temp document
         *    to the final name (rename, or stream-copy fallback), then drop the backup.
         *
         * @return the written document, its name, and how it landed
         * @throws IOException if write fails
         */
        private fun writeAtomically(
            vaultDir: DocumentFile,
            baseFilename: String,
            lastExport: ObsidianExportRecord?,
            content: String,
        ): ObsidianExportResult {
            val uniqueSuffix = UUID.randomUUID().toString().take(8)
            val tempFilename = "$baseFilename.tmp.$uniqueSuffix"
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

                val trackedFilename = lastExport?.filename ?: baseFilename
                val scan =
                    scanVault(
                        vaultDir = vaultDir,
                        baseFilename = baseFilename,
                        wantedNames = setOf(baseFilename, trackedFilename),
                        currentTempUri = tempFile.uri,
                    )
                val target =
                    decideObsidianExportTarget(
                        baseFilename = baseFilename,
                        lastExport = lastExport,
                        existingNames = scan.names,
                    ) { name -> scan.documents[name]?.let(::readContentHash) }
                if (target.disposition == ObsidianExportDisposition.CONFLICT) {
                    Log.w(
                        TAG,
                        "Note $trackedFilename was edited in the vault; writing ${target.filename} instead",
                    )
                }
                val existing = if (target.replacesExisting) scan.documents[target.filename] else null
                val uri =
                    replaceExisting(
                        vaultDir = vaultDir,
                        tempFile = tempFile,
                        existing = existing,
                        filename = target.filename,
                        backupPrefix = baseFilename,
                        uniqueSuffix = uniqueSuffix,
                    )
                return ObsidianExportResult(
                    uri = uri,
                    filename = target.filename,
                    disposition = target.disposition,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: InPlaceOverwriteFailure) {
                // The previous note was being overwritten in place and the write did not
                // finish, so it is now truncated or half-written. The temp document is the
                // only intact copy of the content — keeping it turns total loss into a
                // recoverable file the user can rename, and the next successful export of
                // this note sweeps it away.
                Log.e(TAG, "In-place overwrite failed; keeping ${tempFile?.name} as the surviving copy", e.cause)
                throw checkNotNull(e.cause)
            } catch (e: Exception) {
                // Clean up temp file on any failure
                try {
                    tempFile?.delete()
                } catch (cleanupError: Exception) {
                    Log.w(TAG, "Failed to clean up temp file", cleanupError)
                }
                throw e
            }
        }

        /** Marks the one failure path where deleting the temp document would lose the content. */
        private class InPlaceOverwriteFailure(
            override val cause: Exception,
        ) : Exception(cause)

        /** Every display name in the vault root, plus documents for the names the export needs. */
        private class VaultScan(
            val names: Set<String>,
            val documents: Map<String, DocumentFile>,
        )

        private class VaultChild(
            val documentId: String,
            val name: String,
        )

        /**
         * One directory scan that collects the vault root's display names, materializes
         * documents for [wantedNames], and deletes temp/backup leftovers from earlier crashed
         * exports of the same note. Leftovers are matched by the `$baseFilename.tmp.` /
         * `$baseFilename.bak.` prefixes, so in-flight exports of other recordings are never
         * touched.
         *
         * Names come from a single cursor over the tree's child documents.
         * `DocumentFile.listFiles()` followed by `child.name` costs one Binder round trip per
         * file in the vault root, which on a real vault is hundreds of IPCs per export while
         * the write mutex is held. Only matching children are turned into [DocumentFile]s.
         *
         * The in-flight temp document is skipped by document id, not by the name that was
         * requested: SAF providers are free to alter the display name they assign
         * (ExternalStorageProvider appends the mime extension, and collisions get a " (1)"
         * suffix), and a name-based skip would then fall through to the prefix branch and
         * delete the file this export just wrote.
         */
        private fun scanVault(
            vaultDir: DocumentFile,
            baseFilename: String,
            wantedNames: Set<String>,
            currentTempUri: Uri,
        ): VaultScan {
            val names = mutableSetOf<String>()
            val documents = mutableMapOf<String, DocumentFile>()

            fun accept(
                name: String,
                document: () -> DocumentFile?,
            ) {
                names += name
                when {
                    name in wantedNames -> document()?.let { documents[name] = it }
                    name.startsWith("$baseFilename.tmp.") || name.startsWith("$baseFilename.bak.") ->
                        document()?.let { deleteQuietly(it, "stale export leftover $name") }
                }
            }

            val currentTempDocumentId =
                runCatching { DocumentsContract.getDocumentId(currentTempUri) }.getOrNull()
            val children = if (currentTempDocumentId == null) null else queryChildren(vaultDir)
            if (children != null) {
                for (child in children) {
                    if (child.documentId == currentTempDocumentId) continue
                    accept(child.name) { childDocument(vaultDir, child.documentId) }
                }
                return VaultScan(names, documents)
            }

            // Provider or uri shape the cursor path can't handle: fall back to the per-child
            // DocumentFile walk, which is slower but always works.
            for (child in vaultDir.listFiles()) {
                if (child.uri == currentTempUri) continue
                val name = child.name ?: continue
                accept(name) { child }
            }
            return VaultScan(names, documents)
        }

        /** Document ids and display names of the vault root's children, or null if unavailable. */
        private fun queryChildren(vaultDir: DocumentFile): List<VaultChild>? =
            try {
                val treeUri = vaultDir.uri
                val childrenUri =
                    DocumentsContract.buildChildDocumentsUriUsingTree(
                        treeUri,
                        DocumentsContract.getDocumentId(treeUri),
                    )
                context.contentResolver
                    .query(
                        childrenUri,
                        arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        ),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                val documentId = cursor.getString(0) ?: continue
                                val name = cursor.getString(1) ?: continue
                                add(VaultChild(documentId, name))
                            }
                        }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Child-document query failed; falling back to listFiles()", e)
                null
            }

        private fun childDocument(
            vaultDir: DocumentFile,
            documentId: String,
        ): DocumentFile? =
            try {
                DocumentFile.fromTreeUri(
                    context,
                    DocumentsContract.buildDocumentUriUsingTree(vaultDir.uri, documentId),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve vault child $documentId", e)
                null
            }

        /**
         * Hash of what is in the vault right now, for comparison against the hash of what this
         * app last wrote. An unreadable document returns null, which callers treat as edited:
         * the safe direction is a conflict copy, never a blind overwrite.
         */
        private fun readContentHash(document: DocumentFile): String? =
            try {
                context.contentResolver.openInputStream(document.uri)?.use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                    digest.digest().toLowerHex()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not read ${document.uri} for edit detection", e)
                null
            }

        /**
         * Put the durable temp content in place of the previous export. The previous note
         * is moved to a backup name first and renamed back if promotion fails, so no
         * failure path can leave the vault without either the old or the new note.
         *
         * [backupPrefix] is the recording's base export filename rather than [filename]: the
         * leftover sweep only knows the base prefix, so a backup left behind by a crash must
         * carry it even when the note being replaced is a conflict copy.
         */
        private fun replaceExisting(
            vaultDir: DocumentFile,
            tempFile: DocumentFile,
            existing: DocumentFile?,
            filename: String,
            backupPrefix: String,
            uniqueSuffix: String,
        ): Uri {
            if (existing == null) return promoteTempFile(vaultDir, tempFile, filename)

            if (!existing.renameTo("$backupPrefix.bak.$uniqueSuffix")) {
                // Provider can't rename the old note aside; last resort is overwriting it
                // in place with the already-durable temp content. This opens the existing note
                // "wt", which truncates it before a single byte is copied, so a failure here is
                // the one case where the temp document must outlive the export.
                try {
                    copyDocument(from = tempFile.uri, to = existing.uri)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw InPlaceOverwriteFailure(e)
                }
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

/** How an export landed in the vault. */
enum class ObsidianExportDisposition {
    /** No note existed for this recording; a new one was written. */
    CREATED,

    /** The previous export was still byte-for-byte what this app wrote, so it was replaced. */
    UPDATED,

    /**
     * The note in the vault no longer matches what this app last wrote (or predates hash
     * bookkeeping), so it was left untouched and the new content went to a conflict copy.
     */
    CONFLICT,
}

/** Result of a successful [ObsidianManager.export]. */
data class ObsidianExportResult(
    val uri: Uri,
    val filename: String,
    val disposition: ObsidianExportDisposition,
)

/** Where an export should write, and whether a document of that name is being replaced. */
internal data class ObsidianExportTarget(
    val filename: String,
    val replacesExisting: Boolean,
    val disposition: ObsidianExportDisposition,
)

private const val LowerHexDigits = "0123456789abcdef"

private fun ByteArray.toLowerHex(): String =
    buildString(size * 2) {
        for (byte in this@toLowerHex) {
            val value = byte.toInt() and 0xFF
            append(LowerHexDigits[value ushr 4])
            append(LowerHexDigits[value and 0x0F])
        }
    }

/** SHA-256 of [content]'s UTF-8 bytes, lowercase hex. */
internal fun sha256Hex(content: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .toLowerHex()

/**
 * Picks the first free `Title (timestamp) (conflict N).md` name. The counter sits inside the
 * name rather than after `.md` so Obsidian still indexes the copy as a note.
 */
internal fun buildObsidianConflictFilename(
    baseFilename: String,
    existingNames: Set<String>,
): String {
    val stem = baseFilename.removeSuffix(".md")
    var counter = 1
    while (true) {
        val candidate = "$stem (conflict $counter).md"
        if (candidate !in existingNames) return candidate
        counter++
    }
}

/**
 * Decides where the next export of a note goes, given what this app last wrote there
 * ([lastExport]) and what the vault holds now.
 *
 * Overwriting is allowed only when the tracked document still hashes to exactly the bytes of
 * the last export. Anything else — the user edited the note in Obsidian, the hash could not
 * be read, or the export predates this bookkeeping — is treated as edited, and the new
 * content goes to a conflict copy so the vault content survives.
 *
 * @param hashOfExisting hash of the named document's current content, null if unreadable
 */
internal fun decideObsidianExportTarget(
    baseFilename: String,
    lastExport: ObsidianExportRecord?,
    existingNames: Set<String>,
    hashOfExisting: (String) -> String?,
): ObsidianExportTarget {
    val trackedFilename = lastExport?.filename ?: baseFilename
    if (trackedFilename !in existingNames) {
        // Nothing of ours is there any more. Recreate the canonical note unless some file we
        // never wrote already holds that name.
        return if (baseFilename in existingNames) {
            conflictTarget(baseFilename, existingNames)
        } else {
            ObsidianExportTarget(
                filename = baseFilename,
                replacesExisting = false,
                disposition = ObsidianExportDisposition.CREATED,
            )
        }
    }

    val currentHash = hashOfExisting(trackedFilename)
    return if (lastExport != null && currentHash != null && currentHash == lastExport.contentHash) {
        ObsidianExportTarget(
            filename = trackedFilename,
            replacesExisting = true,
            disposition = ObsidianExportDisposition.UPDATED,
        )
    } else {
        conflictTarget(baseFilename, existingNames)
    }
}

private fun conflictTarget(
    baseFilename: String,
    existingNames: Set<String>,
): ObsidianExportTarget =
    ObsidianExportTarget(
        filename = buildObsidianConflictFilename(baseFilename, existingNames),
        replacesExisting = false,
        disposition = ObsidianExportDisposition.CONFLICT,
    )

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
