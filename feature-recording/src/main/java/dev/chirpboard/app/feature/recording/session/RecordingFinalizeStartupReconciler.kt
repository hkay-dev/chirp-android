package dev.chirpboard.app.feature.recording.session

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.service.RecordingFinalizeWorkRequest
import dev.chirpboard.app.feature.recording.service.StopSnapshot
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingFinalizeStartupReconciler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val sessionJournal: RecordingSessionJournal,
        private val recordingRepository: RecordingRepository,
    ) {
        suspend fun reconcilePendingFinalizations() {
            val stoppingSessions =
                sessionJournal.loadAllEntries().filter { entry ->
                    entry.state == SessionJournalState.STOPPING && entry.recordingId != null
                }

            stoppingSessions.forEach { entry ->
                val recordingId = entry.recordingId ?: return@forEach
                val recording = recordingRepository.getRecording(recordingId) ?: return@forEach
                if (recording.status != RecordingStatus.RECORDING) {
                    return@forEach
                }
                if (RecordingFinalizeWorkRequest.hasUnfinishedWork(context, recordingId)) {
                    return@forEach
                }
                val snapshot = StopSnapshot.fromSessionEntry(entry)
                RecordingFinalizeWorkRequest.enqueue(
                    context = context,
                    snapshot = snapshot,
                    sessionId = entry.sessionId,
                )
            }
        }
    }
