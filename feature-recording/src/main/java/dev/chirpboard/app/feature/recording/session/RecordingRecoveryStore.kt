package dev.chirpboard.app.feature.recording.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

@Singleton
class RecordingRecoveryStore
    @Inject
    constructor(
        private val sessionRecovery: RecordingSessionRecovery,
        private val deferStore: RecordingRecoveryDeferStore,
    ) {
        private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private val _pendingSessions = MutableStateFlow<List<RecoverableRecordingSession>>(emptyList())
        val pendingSessions: StateFlow<List<RecoverableRecordingSession>> = _pendingSessions.asStateFlow()

        private val _deferredSessionIds = MutableStateFlow<Set<UUID>>(emptySet())

        /** Pending sessions the user has not deferred. */
        val actionablePendingSessions: StateFlow<List<RecoverableRecordingSession>> =
            combine(_pendingSessions, _deferredSessionIds) { sessions, deferred ->
                sessions.filter { it.sessionId !in deferred }
            }.stateIn(storeScope, SharingStarted.Eagerly, emptyList())

        init {
            storeScope.launch {
                _deferredSessionIds.value = deferStore.loadDeferredSessionIds()
            }
        }

        suspend fun refresh() {
            _pendingSessions.value = sessionRecovery.scanForRecoverableSessions()
            val pendingIds = _pendingSessions.value.map { it.sessionId }.toSet()
            val retainedDeferred = deferStore.loadDeferredSessionIds().intersect(pendingIds)
            deferStore.retainOnly(retainedDeferred)
            _deferredSessionIds.value = retainedDeferred
        }

        fun deferSession(sessionId: UUID) {
            _deferredSessionIds.value = _deferredSessionIds.value + sessionId
            storeScope.launch {
                deferStore.deferSession(sessionId)
            }
        }

        suspend fun recoverSession(sessionId: UUID): SessionRecoveryResult {
            val result = sessionRecovery.recoverSession(sessionId)
            refresh()
            return result
        }

        suspend fun discardSession(sessionId: UUID): SessionRecoveryResult {
            val result = sessionRecovery.discardSession(sessionId)
            refresh()
            return result
        }

        suspend fun keepSession(sessionId: UUID): SessionRecoveryResult {
            val result = sessionRecovery.keepSession(sessionId)
            refresh()
            return result
        }
    }
