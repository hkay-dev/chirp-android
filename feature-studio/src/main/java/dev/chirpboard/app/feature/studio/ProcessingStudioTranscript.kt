package dev.chirpboard.app.feature.studio

import androidx.compose.runtime.Stable
import dev.chirpboard.app.data.entity.TranscriptTiming
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

private val PROCESSING_STUDIO_WHITESPACE_REGEX = "\\s+".toRegex()

@Stable
data class TranscriptSegment(
    val text: String,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
)

@Stable
sealed interface ProcessingStudioTranscript {
    data object Empty : ProcessingStudioTranscript

    data class Timed(
        val segments: ImmutableList<TranscriptSegment>,
    ) : ProcessingStudioTranscript

    data class Untimed(
        val text: String,
    ) : ProcessingStudioTranscript
}

internal fun ProcessingStudioTranscript.renderedText(): String =
    when (this) {
        ProcessingStudioTranscript.Empty -> ""
        is ProcessingStudioTranscript.Timed -> segments.joinToString(separator = " ") { it.text }
        is ProcessingStudioTranscript.Untimed -> text
    }

internal fun buildProcessingStudioTranscript(
    rawText: String,
    timings: List<TranscriptTiming>,
): ProcessingStudioTranscript {
    val normalizedText = rawText.trim()
    if (normalizedText.isBlank()) return ProcessingStudioTranscript.Empty
    if (timings.isEmpty()) return ProcessingStudioTranscript.Untimed(text = normalizedText)

    val expectedWords = normalizedText.split(PROCESSING_STUDIO_WHITESPACE_REGEX).filter { it.isNotBlank() }
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

    return -1
}
