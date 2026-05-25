package dev.chirpboard.app.feature.recording.session

import dev.chirpboard.app.feature.recording.cleanup.OrphanedAudioCleaner
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingStartupCoordinator
    @Inject
    constructor(
        private val recoveryStore: RecordingRecoveryStore,
        private val orphanedAudioCleaner: OrphanedAudioCleaner,
    ) {
        suspend fun onAppStart() {
            recoveryStore.refresh()
            orphanedAudioCleaner.cleanOrphanedFiles()
        }
    }
