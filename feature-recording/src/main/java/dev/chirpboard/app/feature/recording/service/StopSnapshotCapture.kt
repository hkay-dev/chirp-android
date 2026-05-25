package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import java.io.File
import java.util.UUID

internal object StopSnapshotCapture {
    fun capture(
        recordingStateManager: RecordingStateManager,
        currentRecordingFile: File?,
        currentProfileId: UUID?,
        currentOrigin: RecordingOrigin,
        currentInProgressRecordingId: UUID?,
        currentCorrelationId: String?,
    ): StopSnapshot? {
        val state = recordingStateManager.state.value
        val isPaused = state is RecordingState.Paused
        val filePath =
            currentRecordingFile?.absolutePath ?: when (state) {
                is RecordingState.Recording -> state.audioFilePath
                is RecordingState.Paused -> state.audioFilePath
                else -> null
            }
        val profileId =
            when (state) {
                is RecordingState.Starting -> state.profileId
                is RecordingState.Recording -> state.profileId
                is RecordingState.Paused -> state.profileId
                else -> currentProfileId
            }

        return StopSnapshot(
            origin = state.activeOrigin ?: currentOrigin,
            profileId = profileId,
            recordingId = currentInProgressRecordingId,
            audioFilePath = filePath,
            durationMs = recordingStateManager.getCurrentDurationMs().coerceAtLeast(0L),
            stoppedAtEpochMs = System.currentTimeMillis(),
            wasPaused = isPaused,
            correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
        )
    }
}
