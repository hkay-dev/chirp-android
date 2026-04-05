package dev.chirpboard.app.feature.llm

sealed class GenerationOutcome {
    object Skipped : GenerationOutcome()
    data class Success(val text: String) : GenerationOutcome()
    data class Failure(val error: Throwable) : GenerationOutcome()
}
