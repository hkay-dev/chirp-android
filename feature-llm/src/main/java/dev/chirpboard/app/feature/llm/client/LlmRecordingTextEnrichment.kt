package dev.chirpboard.app.feature.llm.client

import dev.chirpboard.app.core.llm.RecordingTextEnrichment
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlmRecordingTextEnrichment
    @Inject
    constructor(
        private val llmClient: LlmClient,
    ) : RecordingTextEnrichment {
        override suspend fun generateTitle(transcript: String): Result<String> = llmClient.generateTitle(transcript)

        override suspend fun generateSummary(transcript: String): Result<String> = llmClient.generateSummary(transcript)
    }
