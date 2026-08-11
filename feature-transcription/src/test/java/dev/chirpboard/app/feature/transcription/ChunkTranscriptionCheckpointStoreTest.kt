package dev.chirpboard.app.feature.transcription

import android.content.Context
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import dev.chirpboard.app.core.transcription.RecognizedWordTiming
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Persistence contract for per-chunk transcription checkpoints: what a worker retry may
 * reuse (same execution over the same audio), what must be discarded (any fingerprint
 * drift), and how corruption degrades (per chunk, never the whole run).
 */
class ChunkTranscriptionCheckpointStoreTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var store: ChunkTranscriptionCheckpointStore
    private val recordingId: UUID = UUID.randomUUID()
    private lateinit var fingerprint: String

    @Before
    fun setup() {
        val context = mockk<Context>()
        every { context.filesDir } returns temporaryFolder.root
        store = ChunkTranscriptionCheckpointStore(context)
        fingerprint = store.fingerprint(
            executionToken = "token-1",
            audioFile = File(temporaryFolder.root, "audio.m4a"),
            chunkDurationMs = 30_000,
            overlapDurationMs = 2_000,
            sampleRate = 16_000,
        )
    }

    @Test
    fun roundTrips_timedUntimedAndSilentChunks() {
        store.append(
            recordingId,
            fingerprint,
            chunkIndex = 0,
            chunk = ChunkTranscription(
                text = "hello \"quoted\"\nworld\ttab",
                wordTimings = listOf(
                    RecognizedWordTiming("hello", 0L, 400L),
                    RecognizedWordTiming("world", 400L, 900L),
                ),
            ),
        )
        store.append(recordingId, fingerprint, chunkIndex = 1, chunk = ChunkTranscription(text = ""))
        store.append(
            recordingId,
            fingerprint,
            chunkIndex = 2,
            chunk = ChunkTranscription(text = "untimed tail", wordTimings = null),
        )

        val loaded = store.load(recordingId, fingerprint)

        assertEquals(setOf(0, 1, 2), loaded.keys)
        assertEquals("hello \"quoted\"\nworld\ttab", loaded.getValue(0).text)
        assertEquals(
            listOf(
                RecognizedWordTiming("hello", 0L, 400L),
                RecognizedWordTiming("world", 400L, 900L),
            ),
            loaded.getValue(0).wordTimings,
        )
        // A silence (NoSpeech) chunk must round-trip too, or retries re-transcribe silence.
        assertEquals("", loaded.getValue(1).text)
        assertEquals("untimed tail", loaded.getValue(2).text)
        assertNull(loaded.getValue(2).wordTimings)
    }

    @Test
    fun load_discardsEverythingWhenTheFingerprintChanged() {
        store.append(recordingId, fingerprint, chunkIndex = 0, chunk = ChunkTranscription(text = "stale"))

        val otherExecution = store.fingerprint(
            executionToken = "token-2",
            audioFile = File(temporaryFolder.root, "audio.m4a"),
            chunkDurationMs = 30_000,
            overlapDurationMs = 2_000,
            sampleRate = 16_000,
        )
        assertTrue(store.load(recordingId, otherExecution).isEmpty())
        // The mismatched directory is deleted, so even the original fingerprint finds nothing.
        assertTrue(store.load(recordingId, fingerprint).isEmpty())
    }

    @Test
    fun load_skipsACorruptChunkFileButKeepsTheRest() {
        store.append(recordingId, fingerprint, chunkIndex = 0, chunk = ChunkTranscription(text = "good"))
        store.append(recordingId, fingerprint, chunkIndex = 1, chunk = ChunkTranscription(text = "doomed"))
        val chunkFile = File(temporaryFolder.root, "transcription-chunk-checkpoints/$recordingId/chunk-1")
        assertTrue(chunkFile.isFile)
        chunkFile.writeText("v1\n%%% not base64 %%%\ntimed\ngarbage")

        val loaded = store.load(recordingId, fingerprint)

        assertEquals(setOf(0), loaded.keys)
        assertEquals("good", loaded.getValue(0).text)
    }

    @Test
    fun load_skipsAChunkFileTruncatedMidWriteAndAChunkWithABrokenTimingLine() {
        store.append(recordingId, fingerprint, chunkIndex = 0, chunk = ChunkTranscription(text = "good"))
        store.append(recordingId, fingerprint, chunkIndex = 1, chunk = ChunkTranscription(text = "truncated"))
        store.append(recordingId, fingerprint, chunkIndex = 2, chunk = ChunkTranscription(text = "half-timed"))
        val dir = File(temporaryFolder.root, "transcription-chunk-checkpoints/$recordingId")
        // Process death after the version line landed but before the payload did.
        File(dir, "chunk-1").writeText("v1\n")
        // A timing line missing its end timestamp must not be silently dropped from the chunk:
        // stitched-in words with the wrong spans are worse than transcribing the chunk again.
        File(dir, "chunk-2").writeText("v1\n${base64("hi")}\ntimed\n${base64("hi")}\t0")

        val loaded = store.load(recordingId, fingerprint)

        assertEquals(setOf(0), loaded.keys)
    }

    @Test
    fun clear_removesTheRecordingDirectory() {
        store.append(recordingId, fingerprint, chunkIndex = 0, chunk = ChunkTranscription(text = "done"))

        store.clear(recordingId)

        assertFalse(File(temporaryFolder.root, "transcription-chunk-checkpoints/$recordingId").exists())
        assertTrue(store.load(recordingId, fingerprint).isEmpty())
    }

    @Test
    fun load_sweepsCheckpointDirectoriesOlderThanTheMaxAge() {
        val abandonedRecordingId = UUID.randomUUID()
        store.append(abandonedRecordingId, fingerprint, chunkIndex = 0, chunk = ChunkTranscription(text = "old"))
        store.append(recordingId, fingerprint, chunkIndex = 0, chunk = ChunkTranscription(text = "fresh"))

        val lastWriteMs = File(temporaryFolder.root, "transcription-chunk-checkpoints/$abandonedRecordingId")
            .listFiles()!!
            .maxOf(File::lastModified)
        // Age only the abandoned directory past the cutoff.
        File(temporaryFolder.root, "transcription-chunk-checkpoints/$recordingId")
            .listFiles()!!
            .forEach { it.setLastModified(lastWriteMs + ChunkTranscriptionCheckpointStore.MAX_CHECKPOINT_AGE_MS) }
        store.nowMs = { lastWriteMs + ChunkTranscriptionCheckpointStore.MAX_CHECKPOINT_AGE_MS + 1 }

        val loaded = store.load(recordingId, fingerprint)

        assertEquals("fresh", loaded.getValue(0).text)
        assertFalse(File(temporaryFolder.root, "transcription-chunk-checkpoints/$abandonedRecordingId").exists())
    }

    private fun base64(value: String): String =
        java.util.Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
}
