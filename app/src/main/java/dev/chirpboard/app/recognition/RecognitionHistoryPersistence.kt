package dev.chirpboard.app.recognition

import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import java.util.UUID

internal suspend fun persistRecognitionHistoryAtomically(
    rawText: String,
    persistAtomic: suspend (Recording, Transcript) -> Unit
): Result<UUID> {
    val payload = buildRecognitionHistoryPayload(rawText)

    return try {
        persistAtomic(payload.recording, payload.transcript)
        Result.success(payload.recording.id)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

internal fun buildRecognitionHistoryPayload(rawText: String): RecognitionHistoryPayload {
    val recording = Recording(
        title = rawText.take(50).ifBlank { "Voice transcription" },
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
