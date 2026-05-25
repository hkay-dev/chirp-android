package dev.chirpboard.app.feature.llm.client

data class StructuredOutcomeExtraction(
    val tasks: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val followUps: List<String> = emptyList(),
)
