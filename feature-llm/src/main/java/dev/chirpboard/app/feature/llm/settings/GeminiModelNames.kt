package dev.chirpboard.app.feature.llm.settings

/** Stable default for Google AI Studio generateContent calls. */
const val DEFAULT_GEMINI_MODEL = "gemini-3.1-flash-lite"

private val deprecatedModelReplacements =
    mapOf(
        "gemini-3.1-flash-lite-preview" to DEFAULT_GEMINI_MODEL,
        "gemini-3-flash-preview" to "gemini-3.5-flash",
        "gemini-3-pro-preview" to "gemini-3.1-pro-preview",
    )

internal fun resolveGeminiModelName(storedModelName: String?): String {
    val candidate = storedModelName?.trim().orEmpty().ifBlank { DEFAULT_GEMINI_MODEL }
    return deprecatedModelReplacements[candidate] ?: candidate
}
