package dev.chirpboard.app.feature.studio

import dev.chirpboard.app.data.entity.TranscriptTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ProcessingStudioTranscriptTest {
    private val recordingId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @Test
    fun `aligned timing rows build timed transcript`() {
        val transcript =
            buildProcessingStudioTranscript(
                rawText = "hello world again",
                timings =
                    listOf(
                        TranscriptTiming(recordingId, 0, "hello", 0L, 100L),
                        TranscriptTiming(recordingId, 1, "world", 100L, 250L),
                        TranscriptTiming(recordingId, 2, "again", 250L, 400L),
                    ),
            )

        assertTrue(transcript is ProcessingStudioTranscript.Timed)
        transcript as ProcessingStudioTranscript.Timed
        assertEquals(3, transcript.segments.size)
        assertEquals("world", transcript.segments[1].text)
        assertEquals(1, findActiveTranscriptSegmentIndex(transcript, 150L))
    }

    @Test
    fun `missing timing rows build untimed transcript`() {
        val transcript = buildProcessingStudioTranscript(rawText = "hello world", timings = emptyList())

        assertEquals(
            ProcessingStudioTranscript.Untimed(text = "hello world"),
            transcript,
        )
        assertEquals(-1, findActiveTranscriptSegmentIndex(transcript, 50L))
    }

    @Test
    fun `partial timing rows fall back to untimed transcript`() {
        val transcript =
            buildProcessingStudioTranscript(
                rawText = "hello world again",
                timings =
                    listOf(
                        TranscriptTiming(recordingId, 0, "hello", 0L, 100L),
                        TranscriptTiming(recordingId, 1, "world", 100L, 250L),
                    ),
            )

        assertEquals(
            ProcessingStudioTranscript.Untimed(text = "hello world again"),
            transcript,
        )
    }

    @Test
    fun `non monotonic timing rows fall back to untimed transcript`() {
        val transcript =
            buildProcessingStudioTranscript(
                rawText = "hello world",
                timings =
                    listOf(
                        TranscriptTiming(recordingId, 0, "hello", 200L, 300L),
                        TranscriptTiming(recordingId, 1, "world", 100L, 250L),
                    ),
            )

        assertEquals(
            ProcessingStudioTranscript.Untimed(text = "hello world"),
            transcript,
        )
    }

    @Test
    fun `manual correction text falls back to untimed transcript when timings no longer align`() {
        val transcript =
            buildProcessingStudioTranscript(
                rawText = "hello corrected world",
                timings =
                    listOf(
                        TranscriptTiming(recordingId, 0, "hello", 0L, 100L),
                        TranscriptTiming(recordingId, 1, "world", 100L, 250L),
                    ),
            )

        assertEquals(
            ProcessingStudioTranscript.Untimed(text = "hello corrected world"),
            transcript,
        )
    }
}
