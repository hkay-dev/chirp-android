package dev.chirpboard.app.feature.llm.settings

/** Stable default for Google AI Studio generateContent calls. */
const val DEFAULT_GEMINI_MODEL = "gemini-3.1-flash-lite"

private val deprecatedGeminiModels =
    mapOf(
        "gemini-3.1-flash-lite-preview" to DEFAULT_GEMINI_MODEL,
        "gemini-3-flash-preview" to "gemini-3.5-flash",
        "gemini-3-pro-preview" to "gemini-3.1-pro-preview",
    )

private val catalog: Map<LlmProvider, List<LlmModelOption>> =
    mapOf(
        LlmProvider.GEMINI to
            listOf(
                LlmModelOption(DEFAULT_GEMINI_MODEL, "Gemini 3.1 Flash-Lite"),
                LlmModelOption("gemini-2.5-flash", "Gemini 2.5 Flash"),
                LlmModelOption("gemini-3.5-flash", "Gemini 3.5 Flash"),
            ),
        LlmProvider.OPENAI to
            listOf(
                LlmModelOption("gpt-5.5", "GPT-5.5"),
                LlmModelOption("gpt-5.4-mini", "GPT-5.4 Mini"),
                LlmModelOption("gpt-5.4", "GPT-5.4"),
            ),
        LlmProvider.ANTHROPIC to
            listOf(
                LlmModelOption("claude-sonnet-4-6", "Claude Sonnet 4.6"),
                LlmModelOption("claude-haiku-4-5", "Claude Haiku 4.5"),
                LlmModelOption("claude-opus-4-7", "Claude Opus 4.7"),
            ),
        LlmProvider.GROQ to
            listOf(
                LlmModelOption("llama-3.3-70b-versatile", "Llama 3.3 70B"),
                LlmModelOption("llama-3.1-8b-instant", "Llama 3.1 8B Instant"),
                LlmModelOption("openai/gpt-oss-120b", "GPT-OSS 120B"),
            ),
        LlmProvider.CEREBRAS to
            listOf(
                LlmModelOption("gpt-oss-120b", "GPT-OSS 120B"),
                LlmModelOption("llama3.1-8b", "Llama 3.1 8B"),
                LlmModelOption("qwen-3-235b-a22b-instruct-2507", "Qwen 3 235B"),
            ),
    )

fun modelsFor(provider: LlmProvider): List<LlmModelOption> = catalog.getValue(provider)

fun defaultModelFor(provider: LlmProvider): String = modelsFor(provider).first().id

fun resolveModelId(
    provider: LlmProvider,
    storedModelId: String?,
): String {
    val candidate = storedModelId?.trim().orEmpty()
    val normalized =
        when (provider) {
            LlmProvider.GEMINI -> deprecatedGeminiModels[candidate] ?: candidate
            else -> candidate
        }.ifBlank { defaultModelFor(provider) }

    val knownIds = modelsFor(provider).map { it.id }.toSet()
    return if (normalized in knownIds) {
        normalized
    } else {
        defaultModelFor(provider)
    }
}

/** @deprecated Use [resolveModelId] with [LlmProvider.GEMINI]. */
fun resolveGeminiModelName(storedModelName: String?): String =
    resolveModelId(LlmProvider.GEMINI, storedModelName)
