package dev.chirpboard.app.feature.studio

import dev.chirpboard.app.feature.llm.client.TranscriptPassageAction

private val TRANSCRIPT_CORRECTION_TOKEN_REGEX = "\\s+".toRegex()

internal data class TranscriptCorrectionPromotion(
    val original: String,
    val replacement: String,
)

data class TranscriptSelectionResult(
    val action: TranscriptPassageAction,
    val text: String,
)

internal fun analyzeTranscriptCorrectionPromotion(
    sourceText: String,
    correctedText: String,
): TranscriptCorrectionPromotion? {
    val sourceTokens = sourceText.trim().split(TRANSCRIPT_CORRECTION_TOKEN_REGEX).filter { it.isNotBlank() }
    val correctedTokens = correctedText.trim().split(TRANSCRIPT_CORRECTION_TOKEN_REGEX).filter { it.isNotBlank() }

    if (sourceTokens.isEmpty() || correctedTokens.isEmpty() || sourceTokens == correctedTokens) {
        return null
    }

    var prefixLength = 0
    val prefixBound = minOf(sourceTokens.size, correctedTokens.size)
    while (prefixLength < prefixBound && sourceTokens[prefixLength] == correctedTokens[prefixLength]) {
        prefixLength++
    }

    var suffixLength = 0
    while (
        suffixLength < sourceTokens.size - prefixLength &&
        suffixLength < correctedTokens.size - prefixLength &&
        sourceTokens[sourceTokens.lastIndex - suffixLength] == correctedTokens[correctedTokens.lastIndex - suffixLength]
    ) {
        suffixLength++
    }

    val sourceDiff = sourceTokens.subList(prefixLength, sourceTokens.size - suffixLength)
    val correctedDiff = correctedTokens.subList(prefixLength, correctedTokens.size - suffixLength)
    if (sourceDiff.isEmpty() || correctedDiff.isEmpty()) {
        return null
    }

    if (sourceDiff.any { token -> correctedDiff.contains(token) }) {
        return null
    }

    return TranscriptCorrectionPromotion(
        original = sourceDiff.joinToString(" "),
        replacement = correctedDiff.joinToString(" "),
    )
}

internal fun ProcessingStudioState.enterTranscriptEditMode(): ProcessingStudioState =
    exitTranscriptSelectionMode().copy(
        isEditingTranscript = true,
        transcriptDraft = effectiveTranscriptText,
        activeTranscriptSegmentIndex = -1,
    )

internal fun ProcessingStudioState.exitTranscriptEditMode(): ProcessingStudioState =
    copy(
        isEditingTranscript = false,
        transcriptDraft = effectiveTranscriptText,
    )

internal fun ProcessingStudioState.enterTranscriptSelectionMode(): ProcessingStudioState =
    copy(
        isSelectingTranscript = true,
        activeTranscriptSegmentIndex = -1,
        selectedTranscriptPassage = "",
        transcriptSelectionActionInFlight = null,
        transcriptSelectionResult = null,
    )

internal fun ProcessingStudioState.exitTranscriptSelectionMode(): ProcessingStudioState =
    copy(
        isSelectingTranscript = false,
        selectedTranscriptPassage = "",
        transcriptSelectionActionInFlight = null,
        transcriptSelectionResult = null,
    )

internal fun ProcessingStudioState.updateTranscriptSelection(selectedText: String): ProcessingStudioState {
    val normalizedSelection = selectedText.trim()
    if (normalizedSelection == selectedTranscriptPassage) return this

    return copy(
        selectedTranscriptPassage = normalizedSelection,
        transcriptSelectionActionInFlight = null,
        transcriptSelectionResult = null,
    )
}

internal fun ProcessingStudioState.startTranscriptSelectionAction(action: TranscriptPassageAction): ProcessingStudioState =
    copy(
        transcriptSelectionActionInFlight = action,
        transcriptSelectionResult = null,
    )

internal fun ProcessingStudioState.finishTranscriptSelectionAction(
    action: TranscriptPassageAction,
    resultText: String,
): ProcessingStudioState =
    copy(
        transcriptSelectionActionInFlight = null,
        transcriptSelectionResult = TranscriptSelectionResult(action = action, text = resultText.trim()),
    )

internal fun ProcessingStudioState.failTranscriptSelectionAction(): ProcessingStudioState =
    copy(
        transcriptSelectionActionInFlight = null,
    )

internal fun ProcessingStudioState.canEnterTranscriptSelectionMode(): Boolean = renderedTranscriptText.isNotBlank() && !isEditingTranscript

internal fun ProcessingStudioState.validateTranscriptSelectionActionRequest(hasApiKey: Boolean): String? =
    when {
        selectedTranscriptPassage.isBlank() -> "Select transcript text first"
        !hasApiKey -> "Add a Gemini API key in Settings to use transcript tools"
        else -> null
    }

internal fun ProcessingStudioState.matchesTranscriptSelectionRequest(
    selection: String,
    transcriptText: String,
    action: TranscriptPassageAction,
): Boolean =
    isSelectingTranscript &&
        selectedTranscriptPassage == selection &&
        renderedTranscriptText == transcriptText &&
        transcriptSelectionActionInFlight == action

internal fun ProcessingStudioState.canUseTranscriptInteractions(): Boolean = !isEditingTranscript && !isSelectingTranscript
