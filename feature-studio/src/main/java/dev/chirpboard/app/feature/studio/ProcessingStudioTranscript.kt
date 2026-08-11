package dev.chirpboard.app.feature.studio

import androidx.compose.runtime.Stable
import dev.chirpboard.app.data.entity.TranscriptTiming
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

private val PROCESSING_STUDIO_WHITESPACE_REGEX = "\\s+".toRegex()
private val PROCESSING_STUDIO_WORD_REGEX = "\\S+".toRegex()
private val UNTIMED_PARAGRAPH_REGEX = "\\n\\s*\\n".toRegex()

/** Words per LazyColumn item when an oversized paragraph has to be split for rendering. */
private const val UNTIMED_WORDS_PER_CHUNK = 100

@Stable
data class TranscriptSegment(
    val text: String,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    /**
     * Newlines between this word and the previous one in the source text (0 = plain space,
     * 1 = line break, 2 = paragraph break). Rendering honors these so enhanced or hand-corrected
     * transcripts keep their paragraph structure instead of collapsing into one wall of text.
     */
    val precededByLineBreaks: Int = 0,
)

@Stable
sealed interface ProcessingStudioTranscript {
    data object Empty : ProcessingStudioTranscript

    data class Timed(
        val segments: ImmutableList<TranscriptSegment>,
    ) : ProcessingStudioTranscript

    data class Untimed(
        val text: String,
    ) : ProcessingStudioTranscript {
        /**
         * Paragraph-preserving render chunks, precomputed here so the work happens on the
         * transcript-build dispatcher at construction time, never during composition.
         * Not part of equality (derived from [text]).
         */
        val textChunks: ImmutableList<String> = buildUntimedTranscriptChunks(text)
    }
}

internal fun transcriptWordSeparator(lineBreaks: Int): String =
    when {
        lineBreaks <= 0 -> " "
        lineBreaks == 1 -> "\n"
        else -> "\n\n"
    }

internal fun ProcessingStudioTranscript.renderedText(): String =
    when (this) {
        ProcessingStudioTranscript.Empty -> ""
        is ProcessingStudioTranscript.Timed ->
            buildString {
                segments.forEachIndexed { index, segment ->
                    if (index > 0) append(transcriptWordSeparator(segment.precededByLineBreaks))
                    append(segment.text)
                }
            }
        is ProcessingStudioTranscript.Untimed -> text
    }

/**
 * Paragraphs render verbatim (single line breaks included); only a paragraph too large for one
 * LazyColumn item is word-chunked, and copy/share still use the untouched full text.
 */
internal fun buildUntimedTranscriptChunks(text: String): ImmutableList<String> =
    text
        .split(UNTIMED_PARAGRAPH_REGEX)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .flatMap { paragraph ->
            val words = paragraph.split(PROCESSING_STUDIO_WHITESPACE_REGEX).filter { it.isNotBlank() }
            if (words.size <= UNTIMED_WORDS_PER_CHUNK) {
                listOf(paragraph)
            } else {
                words.chunked(UNTIMED_WORDS_PER_CHUNK).map { it.joinToString(" ") }
            }
        }.toImmutableList()

internal fun buildProcessingStudioTranscript(
    rawText: String,
    timings: List<TranscriptTiming>,
): ProcessingStudioTranscript {
    val normalizedText = rawText.trim()
    if (normalizedText.isBlank()) return ProcessingStudioTranscript.Empty
    if (timings.isEmpty()) return ProcessingStudioTranscript.Untimed(text = normalizedText)

    // One pass extracts the words and how many newlines preceded each, so timed segments can
    // reproduce the source's paragraph structure.
    val expectedWords = mutableListOf<String>()
    val lineBreaksBefore = mutableListOf<Int>()
    var previousWordEnd = 0
    for (match in PROCESSING_STUDIO_WORD_REGEX.findAll(normalizedText)) {
        var newlines = 0
        for (position in previousWordEnd until match.range.first) {
            if (normalizedText[position] == '\n' && newlines < 2) newlines++
        }
        expectedWords += match.value
        lineBreaksBefore += newlines
        previousWordEnd = match.range.last + 1
    }
    if (expectedWords.isEmpty()) return ProcessingStudioTranscript.Empty
    if (timings.size != expectedWords.size) {
        return ProcessingStudioTranscript.Untimed(text = normalizedText)
    }

    val segments = mutableListOf<TranscriptSegment>()
    var previousSequenceIndex = -1
    var previousStartMs = -1L
    var previousEndMs = -1L

    for ((index, timing) in timings.withIndex()) {
        if (timing.sequenceIndex != index) return ProcessingStudioTranscript.Untimed(text = normalizedText)
        if (timing.sequenceIndex <= previousSequenceIndex) return ProcessingStudioTranscript.Untimed(text = normalizedText)
        if (timing.text != expectedWords[index]) return ProcessingStudioTranscript.Untimed(text = normalizedText)
        if (timing.startOffsetMs < 0L) return ProcessingStudioTranscript.Untimed(text = normalizedText)
        if (timing.endOffsetMs < timing.startOffsetMs) return ProcessingStudioTranscript.Untimed(text = normalizedText)
        if (timing.startOffsetMs < previousStartMs) return ProcessingStudioTranscript.Untimed(text = normalizedText)
        if (timing.endOffsetMs < previousEndMs) return ProcessingStudioTranscript.Untimed(text = normalizedText)

        segments +=
            TranscriptSegment(
                text = timing.text,
                startTimestampMs = timing.startOffsetMs,
                endTimestampMs = timing.endOffsetMs,
                precededByLineBreaks = lineBreaksBefore[index],
            )

        previousSequenceIndex = timing.sequenceIndex
        previousStartMs = timing.startOffsetMs
        previousEndMs = timing.endOffsetMs
    }

    if (segments.isEmpty()) return ProcessingStudioTranscript.Untimed(text = normalizedText)

    return ProcessingStudioTranscript.Timed(segments = segments.toImmutableList())
}

internal fun findActiveTranscriptSegmentIndex(
    transcript: ProcessingStudioTranscript,
    positionMs: Long,
): Int {
    val timedTranscript = transcript as? ProcessingStudioTranscript.Timed ?: return -1
    val segments = timedTranscript.segments
    if (segments.isEmpty()) return -1

    var low = 0
    var high = segments.lastIndex

    while (low <= high) {
        val mid = (low + high) ushr 1
        val segment = segments[mid]
        when {
            positionMs < segment.startTimestampMs -> high = mid - 1
            positionMs > segment.endTimestampMs -> low = mid + 1
            else -> return mid
        }
    }

    // Word timings have gaps (silences between words and sentences), and the position
    // ticker samples at 10 Hz, so playback regularly lands between segments. Hold the
    // last started word through the gap instead of blinking the highlight off; before
    // the first word there is genuinely nothing to highlight.
    return high
}
