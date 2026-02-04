package dev.chirpboard.app.feature.llm

import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.feature.llm.generator.SummaryGenerator
import dev.chirpboard.app.feature.llm.generator.TitleGenerator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates LLM processing for recordings.
 * - Title generation: Only for APP and WIDGET sources (not KEYBOARD)
 * - Summary generation: For all sources
 */
@Singleton
class LlmProcessor @Inject constructor(
    private val titleGenerator: TitleGenerator,
    private val summaryGenerator: SummaryGenerator
) {
    data class ProcessingResult(
        val title: String?,
        val summary: String?
    )

    /**
     * Process transcript to generate title and summary.
     * @param transcript The transcript text
     * @param source Recording source (affects whether title is generated)
     * @return ProcessingResult with generated title (if applicable) and summary
     */
    suspend fun process(
        transcript: String,
        source: RecordingSource
    ): ProcessingResult {
        val title = if (source != RecordingSource.KEYBOARD) {
            titleGenerator.generate(transcript)
        } else null

        val summary = summaryGenerator.generate(transcript)

        return ProcessingResult(title = title, summary = summary)
    }
}
