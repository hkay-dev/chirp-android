package dev.chirpboard.app.feature.recording.service

import androidx.work.Data
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.feature.recording.session.RecordingSessionEntry
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
) {
    fun toWorkData(sessionId: UUID?): Data =
        Data.Builder()
            .putString(RecordingFinalizeWorkKeys.INPUT_ORIGIN, origin.name)
            .putString(RecordingFinalizeWorkKeys.INPUT_PROFILE_ID, profileId?.toString())
            .putString(RecordingFinalizeWorkKeys.INPUT_RECORDING_ID, recordingId?.toString())
            .putString(RecordingFinalizeWorkKeys.INPUT_AUDIO_FILE_PATH, audioFilePath)
            .putLong(RecordingFinalizeWorkKeys.INPUT_DURATION_MS, durationMs)
            .putLong(RecordingFinalizeWorkKeys.INPUT_STOPPED_AT_EPOCH_MS, stoppedAtEpochMs)
            .putBoolean(RecordingFinalizeWorkKeys.INPUT_WAS_PAUSED, wasPaused)
            .putString(RecordingFinalizeWorkKeys.INPUT_CORRELATION_ID, correlationId)
            .putString(RecordingFinalizeWorkKeys.INPUT_SESSION_ID, sessionId?.toString())
            .build()

    companion object {
        fun fromWorkData(data: Data): StopSnapshot? {
            val originName = data.getString(RecordingFinalizeWorkKeys.INPUT_ORIGIN) ?: return null
            val correlationId =
                data.getString(RecordingFinalizeWorkKeys.INPUT_CORRELATION_ID)
                    ?: ReliabilityEventLogger.newCorrelationId("record")
            return StopSnapshot(
                origin = runCatching { RecordingOrigin.valueOf(originName) }.getOrNull() ?: return null,
                profileId = data.getString(RecordingFinalizeWorkKeys.INPUT_PROFILE_ID)?.let(UUID::fromString),
                recordingId = data.getString(RecordingFinalizeWorkKeys.INPUT_RECORDING_ID)?.let(UUID::fromString),
                audioFilePath = data.getString(RecordingFinalizeWorkKeys.INPUT_AUDIO_FILE_PATH),
                durationMs = data.getLong(RecordingFinalizeWorkKeys.INPUT_DURATION_MS, 0L),
                stoppedAtEpochMs = data.getLong(RecordingFinalizeWorkKeys.INPUT_STOPPED_AT_EPOCH_MS, 0L),
                wasPaused = data.getBoolean(RecordingFinalizeWorkKeys.INPUT_WAS_PAUSED, false),
                correlationId = correlationId,
            )
        }

        fun fromSessionEntry(entry: RecordingSessionEntry): StopSnapshot =
            StopSnapshot(
                origin = entry.origin,
                profileId = entry.profileId,
                recordingId = entry.recordingId,
                audioFilePath = entry.audioPath,
                durationMs = 0L,
                stoppedAtEpochMs = entry.lastHeartbeatEpochMs,
                wasPaused = false,
                correlationId = entry.correlationId ?: ReliabilityEventLogger.newCorrelationId("record"),
            )
    }
}

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
