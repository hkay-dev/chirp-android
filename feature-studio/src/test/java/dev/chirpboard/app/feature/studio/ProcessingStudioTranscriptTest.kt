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
    fun `gaps between words hold the last started word instead of dropping the highlight`() {
        val transcript =
            buildProcessingStudioTranscript(
                rawText = "hello world",
                timings =
                    listOf(
                        TranscriptTiming(recordingId, 0, "hello", 100L, 200L),
                        TranscriptTiming(recordingId, 1, "world", 500L, 600L),
                    ),
            ) as ProcessingStudioTranscript.Timed

        assertEquals(0, findActiveTranscriptSegmentIndex(transcript, 350L))
        assertEquals(1, findActiveTranscriptSegmentIndex(transcript, 700L))
        assertEquals(-1, findActiveTranscriptSegmentIndex(transcript, 50L))
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
    fun `timed transcript preserves paragraph breaks in segments and rendered text`() {
        val transcript =
            buildProcessingStudioTranscript(
                rawText = "hello world\nagain\n\ndone",
                timings =
                    listOf(
                        TranscriptTiming(recordingId, 0, "hello", 0L, 100L),
                        TranscriptTiming(recordingId, 1, "world", 100L, 250L),
                        TranscriptTiming(recordingId, 2, "again", 250L, 400L),
                        TranscriptTiming(recordingId, 3, "done", 400L, 500L),
                    ),
            ) as ProcessingStudioTranscript.Timed

        assertEquals(0, transcript.segments[1].precededByLineBreaks)
        assertEquals(1, transcript.segments[2].precededByLineBreaks)
        assertEquals(2, transcript.segments[3].precededByLineBreaks)
        assertEquals("hello world\nagain\n\ndone", transcript.renderedText())
    }

    @Test
    fun `untimed chunks keep paragraphs whole and split only oversized ones`() {
        val longParagraph = (1..250).joinToString(" ") { "word$it" }
        val text = "first paragraph\nstill first\n\n$longParagraph\n\nlast paragraph"

        val chunks = buildUntimedTranscriptChunks(text)

        assertEquals("first paragraph\nstill first", chunks.first())
        assertEquals("last paragraph", chunks.last())
        // 250 words split at 100 per chunk.
        assertEquals(5, chunks.size)
        assertTrue(chunks[1].startsWith("word1 "))
        assertTrue(chunks[3].endsWith(" word250"))
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
