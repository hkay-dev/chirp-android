package dev.chirpboard.app.feature.transcription

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.transcription.RecognizedWordTiming
import dev.chirpboard.app.core.util.DurableFiles
import java.io.File
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists per-chunk transcription results across WorkManager retry attempts.
 *
 * The chunked local pipeline transcribes a long recording as dozens of ~30s chunks. A
 * retryable engine failure on any one chunk fails the whole attempt, and without a
 * checkpoint the retry re-transcribes every chunk from zero: a transient error at chunk
 * 47 of 50 costs the user a full re-run per attempt before the worker gives up. This
 * store keeps each finished chunk on disk so a retry only redoes the chunks that never
 * completed.
 *
 * Checkpoints are scoped to one enqueued execution over one exact audio file: the
 * fingerprint binds the execution token (stable across retry attempts of the same work,
 * fresh for every user-initiated retranscribe, so a retranscribe after a model update
 * never reuses old results) plus the audio file's path, byte length and mtime, and the
 * chunking parameters. Any mismatch discards the stored chunks. A corrupt chunk file is
 * skipped individually; that chunk is simply transcribed again.
 *
 * Chunk text and word timings are stored chunk-relative, exactly as the engine returned
 * them; the processor re-applies recording offsets on every run. Writes are best-effort
 * (temp file + rename): a failed checkpoint write never fails the transcription itself.
 */
@Singleton
class ChunkTranscriptionCheckpointStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @VisibleForTesting
    internal var nowMs: () -> Long = System::currentTimeMillis

    private val root: File
        get() = File(context.filesDir, ROOT_DIR_NAME)

    fun fingerprint(
        executionToken: String,
        audioFile: File,
        chunkDurationMs: Long,
        overlapDurationMs: Long,
        sampleRate: Int,
    ): String =
        listOf(
            FORMAT_VERSION,
            executionToken,
            audioFile.absolutePath,
            audioFile.length(),
            audioFile.lastModified(),
            chunkDurationMs,
            overlapDurationMs,
            sampleRate,
        ).joinToString("|")

    /**
     * Returns the checkpointed chunks for [recordingId], keyed by chunk index, or an empty
     * map when none match [fingerprint]. A fingerprint mismatch deletes the stored chunks:
     * they describe a different execution or a different audio file and must never be
     * stitched into this run.
     */
    internal fun load(recordingId: UUID, fingerprint: String): Map<Int, ChunkTranscription> {
        sweepStale()
        val dir = recordingDir(recordingId)
        val storedFingerprint =
            runCatching { File(dir, FINGERPRINT_FILE_NAME).readText() }.getOrNull()
        if (storedFingerprint != fingerprint) {
            if (dir.exists()) {
                Log.i(TAG, "Discarding chunk checkpoints for $recordingId: fingerprint changed")
                dir.deleteRecursively()
            }
            return emptyMap()
        }
        val chunks = mutableMapOf<Int, ChunkTranscription>()
        dir.listFiles().orEmpty().forEach { file ->
            if (!file.name.startsWith(CHUNK_FILE_PREFIX)) return@forEach
            val index = file.name.removePrefix(CHUNK_FILE_PREFIX).toIntOrNull() ?: return@forEach
            parseChunkFile(file)?.let { chunks[index] = it }
        }
        if (chunks.isNotEmpty()) {
            Log.i(TAG, "Resuming transcription of $recordingId with ${chunks.size} checkpointed chunks")
        }
        return chunks
    }

    /**
     * Persists one finished chunk. Callers must have called [load] with the same
     * [fingerprint] first (it clears any stale directory), so an existing fingerprint file
     * is already known to match.
     */
    internal fun append(recordingId: UUID, fingerprint: String, chunkIndex: Int, chunk: ChunkTranscription) {
        runCatching {
            val dir = recordingDir(recordingId)
            if (!dir.isDirectory && !dir.mkdirs()) {
                Log.w(TAG, "Could not create chunk checkpoint directory for $recordingId")
                return
            }
            val fingerprintFile = File(dir, FINGERPRINT_FILE_NAME)
            if (!fingerprintFile.exists()) {
                writeAtomically(fingerprintFile, fingerprint)
            }
            writeAtomically(File(dir, "$CHUNK_FILE_PREFIX$chunkIndex"), encodeChunk(chunk))
        }.onFailure { error ->
            Log.w(TAG, "Failed to checkpoint chunk $chunkIndex for $recordingId", error)
        }
    }

    fun clear(recordingId: UUID) {
        runCatching { recordingDir(recordingId).deleteRecursively() }
    }

    private fun recordingDir(recordingId: UUID): File = File(root, recordingId.toString())

    /**
     * Drops checkpoint directories no worker attempt has touched in [MAX_CHECKPOINT_AGE_MS].
     * Terminal worker paths clear their own directory; this catches executions that never
     * came back (process death after the final retry, cleared app data races, etc.).
     */
    private fun sweepStale() {
        val cutoff = nowMs() - MAX_CHECKPOINT_AGE_MS
        root.listFiles().orEmpty().forEach { dir ->
            val newestWrite = dir.listFiles().orEmpty().maxOfOrNull(File::lastModified) ?: dir.lastModified()
            if (newestWrite < cutoff) {
                dir.deleteRecursively()
            }
        }
    }

    /**
     * Chunk file layout (line-oriented; free text is base64 so it can never break framing):
     * ```
     * v1
     * <base64 chunk text>
     * timed|untimed
     * <base64 word>\t<startMs>\t<endMs>   (one line per word, timed only)
     * ```
     */
    private fun encodeChunk(chunk: ChunkTranscription): String =
        buildString {
            appendLine(FORMAT_VERSION)
            appendLine(encodeText(chunk.text))
            val timings = chunk.wordTimings
            if (timings == null) {
                append(UNTIMED_MARKER)
            } else {
                append(TIMED_MARKER)
                timings.forEach { timing ->
                    appendLine()
                    append(encodeText(timing.text))
                    append('\t').append(timing.startTimestampMs)
                    append('\t').append(timing.endTimestampMs)
                }
            }
        }

    /**
     * A malformed chunk file yields null and the chunk is transcribed again, so every parse
     * failure is recoverable. Each field is still checked explicitly rather than left to a
     * blanket catch: a truncated file (process death mid-write on a filesystem that lost the
     * rename) differs from a corrupt payload, and the log line says which one happened.
     */
    private fun parseChunkFile(file: File): ChunkTranscription? {
        val lines = runCatching { file.readLines() }.getOrNull() ?: return discard(file, "unreadable")
        if (lines.size < MIN_CHUNK_FILE_LINES || lines[0] != FORMAT_VERSION) {
            return discard(file, "truncated or unknown format")
        }
        val text = decodeText(lines[1]) ?: return discard(file, "undecodable text")
        return when (lines[2]) {
            UNTIMED_MARKER -> ChunkTranscription(text = text, wordTimings = null)
            TIMED_MARKER -> {
                val timings =
                    lines.drop(3).map { line ->
                        parseWordTiming(line) ?: return discard(file, "malformed word timing")
                    }
                ChunkTranscription(text = text, wordTimings = timings)
            }
            else -> discard(file, "unknown timing marker")
        }
    }

    private fun parseWordTiming(line: String): RecognizedWordTiming? {
        val fields = line.split('\t')
        if (fields.size != WORD_TIMING_FIELD_COUNT) return null
        val text = decodeText(fields[0]) ?: return null
        val start = fields[1].toLongOrNull() ?: return null
        val end = fields[2].toLongOrNull() ?: return null
        return RecognizedWordTiming(text = text, startTimestampMs = start, endTimestampMs = end)
    }

    private fun discard(file: File, reason: String): ChunkTranscription? {
        Log.w(TAG, "Discarding checkpoint ${file.name}: $reason")
        return null
    }

    private fun writeAtomically(target: File, payload: String) {
        // ".partial" is the suffix this store's readers and sweeper are written around: a
        // crash-orphaned staging file neither parses as a chunk nor as the fingerprint.
        runCatching { DurableFiles.writeTextAtomically(target, payload, stagingSuffix = ".partial") }
            .onFailure { Log.w(TAG, "Failed to replace checkpoint file ${target.name}", it) }
    }

    private fun encodeText(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeText(value: String): String? =
        runCatching { String(Base64.getDecoder().decode(value), Charsets.UTF_8) }.getOrNull()

    companion object {
        private const val TAG = "ChunkCheckpointStore"
        private const val ROOT_DIR_NAME = "transcription-chunk-checkpoints"
        private const val FINGERPRINT_FILE_NAME = "fingerprint"
        private const val CHUNK_FILE_PREFIX = "chunk-"
        private const val FORMAT_VERSION = "v1"
        private const val TIMED_MARKER = "timed"
        private const val UNTIMED_MARKER = "untimed"

        /** Version line, base64 text, timing marker. Word-timing lines are optional. */
        private const val MIN_CHUNK_FILE_LINES = 3
        private const val WORD_TIMING_FIELD_COUNT = 3

        @VisibleForTesting
        internal const val MAX_CHECKPOINT_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
