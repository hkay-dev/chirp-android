package dev.chirpboard.app.core.transcription

import java.util.UUID

/**
 * Narrow contract for scheduling background transcription work.
 */
interface TranscriptionScheduler {
    suspend fun enqueue(
        recordingId: UUID,
        correlationId: String? = null,
    ): String
}
