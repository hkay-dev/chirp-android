package dev.chirpboard.app.feature.transcription

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.transcription.RecognizedWordTiming
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

    private fun parseChunkFile(file: File): ChunkTranscription? =
        runCatching {
            val lines = file.readLines()
            if (lines.getOrNull(0) != FORMAT_VERSION) return@runCatching null
            val text = decodeText(lines[1])
            when (lines[2]) {
                UNTIMED_MARKER -> ChunkTranscription(text = text, wordTimings = null)
                TIMED_MARKER ->
                    ChunkTranscription(
                        text = text,
                        wordTimings =
                            lines.drop(3).map { line ->
                                val fields = line.split('\t')
                                RecognizedWordTiming(
                                    text = decodeText(fields[0]),
                                    startTimestampMs = fields[1].toLong(),
                                    endTimestampMs = fields[2].toLong(),
                                )
                            },
                    )
                else -> null
            }
        }.getOrNull()

    private fun writeAtomically(target: File, payload: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            temp.writeText(payload)
            if (!temp.renameTo(target)) {
                Log.w(TAG, "Failed to atomically replace checkpoint file ${target.name}")
            }
        } finally {
            if (temp.exists() && !temp.delete()) {
                Log.w(TAG, "Failed to clean temporary checkpoint file ${temp.name}")
            }
        }
    }

    private fun encodeText(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeText(value: String): String =
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)

    companion object {
        private const val TAG = "ChunkCheckpointStore"
        private const val ROOT_DIR_NAME = "transcription-chunk-checkpoints"
        private const val FINGERPRINT_FILE_NAME = "fingerprint"
        private const val CHUNK_FILE_PREFIX = "chunk-"
        private const val FORMAT_VERSION = "v1"
        private const val TIMED_MARKER = "timed"
        private const val UNTIMED_MARKER = "untimed"

        @VisibleForTesting
        internal const val MAX_CHECKPOINT_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
