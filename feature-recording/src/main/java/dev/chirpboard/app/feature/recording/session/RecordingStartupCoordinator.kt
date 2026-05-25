package dev.chirpboard.app.feature.recording.session

import dev.chirpboard.app.core.recording.KeyboardPendingStopStore
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.feature.recording.cleanup.OrphanedAudioCleaner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class RecordingStartupCoordinator
    @Inject
    constructor(
        private val recoveryStore: RecordingRecoveryStore,
        private val orphanedAudioCleaner: OrphanedAudioCleaner,
        private val sessionJournal: RecordingSessionJournal,
        private val pendingStopStore: KeyboardPendingStopStore,
        private val recordingStateManager: RecordingStateManager,
        private val finalizeStartupReconciler: RecordingFinalizeStartupReconciler,
    ) {
        suspend fun onAppStart() {
            withContext(Dispatchers.IO) {
                sessionJournal.pruneAbandonedEntries()
                pendingStopStore.reconcileStale(recordingStateManager.state.value)
                finalizeStartupReconciler.reconcilePendingFinalizations()
            }
            recoveryStore.refresh()
            orphanedAudioCleaner.cleanOrphanedFiles()
        }
    }
