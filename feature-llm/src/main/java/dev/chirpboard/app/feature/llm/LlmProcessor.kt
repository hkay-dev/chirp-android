package dev.chirpboard.app.feature.llm

import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.feature.llm.client.LlmClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates LLM processing for recordings.
 * - Title generation: Only for APP and WIDGET sources (not KEYBOARD)
 * - Summary generation: For all sources
 */
@Singleton
class LlmProcessor
    @Inject
    constructor(
        private val llmClient: LlmClient,
    ) {
        data class ProcessingResult(
            val title: String?,
            val summary: String?,
        )

        /**
         * Process transcript to generate title and summary.
         * @param transcript The transcript text
         * @param source Recording source (affects whether title is generated)
         * @return ProcessingResult with generated title (if applicable) and summary
         */
        suspend fun process(
            transcript: String,
            source: RecordingSource,
        ): ProcessingResult {
            if (transcript.isBlank()) return ProcessingResult(null, null)

            val title =
                if (source != RecordingSource.KEYBOARD) {
                    val truncated = transcript.split(" ").take(500).joinToString(" ")
                    llmClient.generateTitle(truncated).getOrNull()
                } else {
                    null
                }

            val summary = llmClient.generateSummary(transcript).getOrNull()

            return ProcessingResult(title = title, summary = summary)
        }
    }
