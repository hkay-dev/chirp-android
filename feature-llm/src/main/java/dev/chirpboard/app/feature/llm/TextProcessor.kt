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
            private const val LOSSLESS_TRANSCRIPT_MANDATE =
                """# LOSSLESS TRANSCRIPT MANDATE
Every source idea and meaningful word must survive in the output. Never omit the opening or closing portion of the transcript. Filler removal may remove only non-semantic hesitation sounds. If uncertain whether words carry meaning, keep them.

"""
            // Smart detection scores signals instead of firing on the first substring hit.
            // Plain contains() misread ordinary dictation: "hi " matched inside "sushi",
            // "thanks" matched any polite sign-off, "class " matched "the class was", and a
            // single parenthesis anywhere made a transcript "code". Word tokens are matched
            // on word boundaries, symbol tokens literally, and a mode only wins once its
            // score reaches DETECTION_THRESHOLD - so one weak hint keeps the Formal default.
            private const val DETECTION_THRESHOLD = 2
            private const val STRONG_SIGNAL = 2
            private const val WEAK_SIGNAL = 1

            // Only shows up in prose that really is an email opener or sign-off.
            private val STRONG_EMAIL_WORDS =
                listOf(
                    "dear",
                    "regards",
                    "sincerely",
                    "cordially",
                )

            // Ordinary conversational words; two of them together suggest an email.
            private val WEAK_EMAIL_WORDS =
                listOf(
                    "hi",
                    "hey",
                    "hello",
                    "thanks",
                    "thank you",
                    "attached",
                    "please find",
                    "following up",
                    "let me know",
                )

            // Declaration and control-flow keywords that rarely open a word in dictation.
            private val STRONG_CODE_WORDS =
                listOf(
                    "function",
                    "def",
                    "elif",
                    "boolean",
                    "println",
                    "nullptr",
                    "async",
                    "await",
                )

            // Real English words too, so they need a second signal.
            private val WEAK_CODE_WORDS =
                listOf(
                    "class",
                    "var",
                    "const",
                    "public",
                    "private",
                    "protected",
                    "static",
                    "import",
                    "export",
                    "return",
                    "void",
                    "null",
                )

            private val STRONG_EMAIL_REGEXES = STRONG_EMAIL_WORDS.toWordRegexes()
            private val WEAK_EMAIL_REGEXES = WEAK_EMAIL_WORDS.toWordRegexes()
            private val STRONG_CODE_REGEXES = STRONG_CODE_WORDS.toWordRegexes()
            private val WEAK_CODE_REGEXES = WEAK_CODE_WORDS.toWordRegexes()

            private val STRONG_CODE_SYMBOLS =
                listOf(
                    "->",
                    "=>",
                    "::",
                    "&&",
                    "||",
                    "==",
                    "!=",
                    "if (",
                    "for (",
                    "while (",
                )

            private val CODE_PUNCTUATION_REGEX = Regex("[(){}\\[\\];]")

            // A lone bracket is punctuation, not code. A cluster of them is one weak hint.
            private const val CODE_PUNCTUATION_MIN = 3
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
        ): String {
            val modePrompt =
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
            return LOSSLESS_TRANSCRIPT_MANDATE + modePrompt
        }

        internal fun detectContentType(text: String): ProcessingMode {
            val lowerText = text.lowercase()

            if (emailScore(lowerText) >= DETECTION_THRESHOLD) {
                return ProcessingMode.Email
            }

            if (codeScore(lowerText) >= DETECTION_THRESHOLD) {
                return ProcessingMode.Code
            }

            return ProcessingMode.Formal
        }

        private fun emailScore(lowerText: String): Int =
            STRONG_EMAIL_REGEXES.count { it.containsMatchIn(lowerText) } * STRONG_SIGNAL +
                WEAK_EMAIL_REGEXES.count { it.containsMatchIn(lowerText) } * WEAK_SIGNAL

        private fun codeScore(lowerText: String): Int {
            val punctuationHits = CODE_PUNCTUATION_REGEX.findAll(lowerText).count()
            val punctuationScore = if (punctuationHits >= CODE_PUNCTUATION_MIN) WEAK_SIGNAL else 0
            return STRONG_CODE_REGEXES.count { it.containsMatchIn(lowerText) } * STRONG_SIGNAL +
                STRONG_CODE_SYMBOLS.count { lowerText.contains(it) } * STRONG_SIGNAL +
                WEAK_CODE_REGEXES.count { it.containsMatchIn(lowerText) } * WEAK_SIGNAL +
                punctuationScore
        }
    }

private fun List<String>.toWordRegexes(): List<Regex> = map { Regex("\\b" + Regex.escape(it) + "\\b") }
