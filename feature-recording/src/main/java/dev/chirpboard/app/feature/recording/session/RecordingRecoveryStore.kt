package dev.chirpboard.app.feature.recording.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingRecoveryStore
    @Inject
    constructor(
        private val sessionRecovery: RecordingSessionRecovery,
    ) {
        private val _pendingSessions = MutableStateFlow<List<RecoverableRecordingSession>>(emptyList())
        val pendingSessions: StateFlow<List<RecoverableRecordingSession>> = _pendingSessions.asStateFlow()

        suspend fun refresh() {
            _pendingSessions.value = sessionRecovery.scanForRecoverableSessions()
        }

        suspend fun recoverSession(sessionId: java.util.UUID): SessionRecoveryResult {
            val result = sessionRecovery.recoverSession(sessionId)
            refresh()
            return result
        }

        suspend fun discardSession(sessionId: java.util.UUID): SessionRecoveryResult {
            val result = sessionRecovery.discardSession(sessionId)
            refresh()
            return result
        }

        suspend fun keepSession(sessionId: java.util.UUID): SessionRecoveryResult {
            val result = sessionRecovery.keepSession(sessionId)
            refresh()
            return result
        }
    }
