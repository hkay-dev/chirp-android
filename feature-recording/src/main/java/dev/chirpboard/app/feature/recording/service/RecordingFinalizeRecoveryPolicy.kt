package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.session.RecordingSessionJournal
import java.io.File
import java.util.UUID

internal object RecordingFinalizeRecoveryPolicy {
    fun hasRecoverableArtifacts(
        sessionJournal: RecordingSessionJournal,
        sessionId: UUID?,
        snapshot: StopSnapshot?,
    ): Boolean {
        val journalPaths =
            sessionId
                ?.let(sessionJournal::findBySessionId)
                ?.let(sessionJournal::referencedPathsFor)
                .orEmpty()
        val snapshotPaths = listOfNotNull(snapshot?.audioFilePath)

        return (journalPaths + snapshotPaths)
            .distinct()
            .any { path -> File(path).exists() }
    }

    suspend fun cleanupUnrecoverable(
        sessionJournal: RecordingSessionJournal,
        recordingRepository: RecordingRepository,
        sessionId: UUID?,
        snapshot: StopSnapshot?,
    ) {
        sessionId?.let { sessionJournal.markAbandoned(it) }
        snapshot?.recordingId?.let { recordingRepository.deleteAbandonedInProgressRecording(it) }
    }
}
