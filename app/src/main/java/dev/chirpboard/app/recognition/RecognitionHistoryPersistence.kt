package dev.chirpboard.app.recognition

import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import java.util.UUID

internal suspend fun persistRecognitionHistoryAtomically(
    rawText: String,
    fallbackTitle: String = DEFAULT_RECOGNITION_HISTORY_TITLE,
    persistAtomic: suspend (Recording, Transcript) -> Unit
): Result<UUID> {
    val payload = buildRecognitionHistoryPayload(rawText, fallbackTitle)

    return try {
        persistAtomic(payload.recording, payload.transcript)
        Result.success(payload.recording.id)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Result.failure(e)
    }
}

// I18N-08: UI callers pass the resource-backed fallback title; the default keeps this pure.
internal const val DEFAULT_RECOGNITION_HISTORY_TITLE = "Voice transcription"

internal fun buildRecognitionHistoryPayload(
    rawText: String,
    fallbackTitle: String = DEFAULT_RECOGNITION_HISTORY_TITLE,
): RecognitionHistoryPayload {
    val recording = Recording(
        title = rawText.take(50).ifBlank { fallbackTitle },
        audioPath = "",
        source = RecordingSource.KEYBOARD,
        status = RecordingStatus.COMPLETED
    )

    val transcript = Transcript(
        recordingId = recording.id,
        rawText = rawText
    )

    return RecognitionHistoryPayload(recording, transcript)
}

internal data class RecognitionHistoryPayload(
    val recording: Recording,
    val transcript: Transcript
)
