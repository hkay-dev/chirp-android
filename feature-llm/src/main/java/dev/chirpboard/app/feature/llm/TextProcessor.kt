package dev.chirpboard.app.feature.llm

import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.llm.client.TranscriptLlmContext
import dev.chirpboard.app.feature.llm.model.ProcessingMode
import dev.chirpboard.app.feature.llm.model.ProcessingModeDefaults
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level text processor that uses LlmClient for processing.
 * Handles mode selection and smart mode detection.
 */
@Singleton
class TextProcessor
    @Inject
    constructor(
        private val llmClient: LlmClient,
        private val modeRepository: ProcessingModeRepository,
    ) {
        companion object {
            private val CODE_PATTERNS =
                listOf(
                    "function",
                    "def ",
                    "class ",
                    "var ",
                    "const ",
                    "let ",
                    "public ",
                    "private ",
                    "protected ",
                    "static ",
                    "import ",
                    "export ",
                    "return ",
                    "if (",
                    "for (",
                    "while (",
                    "->",
                    "=>",
                    "::",
                    "&&",
                    "||",
                    "==",
                    "!=",
                )
            private val CODE_REGEX = Regex("[(){}\\[\\];]")

            private val EMAIL_PATTERNS =
                listOf(
                    "dear",
                    "hi ",
                    "hello",
                    "regards",
                    "sincerely",
                    "best regards",
                    "kind regards",
                    "thank you",
                    "thanks",
                )
        }

        suspend fun process(
            text: String,
            mode: ProcessingMode,
        ): Result<String> {
            return process(llmClient.createTranscriptContext(text), mode)
        }

        suspend fun process(
            context: TranscriptLlmContext,
            mode: ProcessingMode,
        ): Result<String> {
            val prompt = resolvePrompt(context.transcript, mode)
            return llmClient.process(context, prompt)
        }

        suspend fun resolvePromptForSnapshot(
            text: String,
            mode: ProcessingMode,
        ): String? = resolvePrompt(text, mode)

        private suspend fun resolvePrompt(
            text: String,
            mode: ProcessingMode,
        ): String =
            when (mode) {
                is ProcessingMode.Smart -> {
                    val detectedId = detectContentType(text).id
                    modeRepository.getPrompt(detectedId)
                        ?: ProcessingModeDefaults.defaultPrompt(detectedId)
                        ?: error("No prompt available for detected mode $detectedId")
                }

                is ProcessingMode.Custom ->
                    mode.customPrompt.ifBlank {
                        modeRepository.getPrompt(ProcessingModeDefaults.DEFAULT_MODE_ID)
                            ?: ProcessingModeDefaults.defaultPrompt(ProcessingModeDefaults.DEFAULT_MODE_ID)!!
                    }

                else ->
                    modeRepository.getPrompt(mode.id)
                        ?: mode.prompt
                        ?: error("No prompt available for mode ${mode.id}")
            }

        private fun detectContentType(text: String): ProcessingMode {
            val lowerText = text.lowercase()

            if (EMAIL_PATTERNS.any { lowerText.contains(it) }) {
                return ProcessingMode.Email
            }

            if (CODE_PATTERNS.any { lowerText.contains(it) } || CODE_REGEX.containsMatchIn(text)) {
                return ProcessingMode.Code
            }

            return ProcessingMode.Formal
        }
    }
