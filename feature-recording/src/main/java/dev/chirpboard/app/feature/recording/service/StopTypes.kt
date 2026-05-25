package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.recording.RecordingOrigin
import java.util.UUID

data class StopSnapshot(
    val origin: RecordingOrigin,
    val profileId: UUID?,
    val recordingId: UUID?,
    val audioFilePath: String?,
    val durationMs: Long,
    val stoppedAtEpochMs: Long,
    val wasPaused: Boolean,
    val correlationId: String,
)

sealed class StopPersistenceResult {
    data class SavedAndQueued(val recordingId: UUID) : StopPersistenceResult()
    data class SavedPendingRecovery(
        val recordingId: UUID,
        val message: String,
        val cause: Throwable? = null,
    ) : StopPersistenceResult()
    data class PersistenceFailed(
        val message: String,
        val cause: Throwable? = null,
    ) : StopPersistenceResult()
    object NoAudioFile : StopPersistenceResult()
}
