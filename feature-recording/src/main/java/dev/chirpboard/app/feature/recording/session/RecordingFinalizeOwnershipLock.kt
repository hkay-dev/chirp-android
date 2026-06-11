package dev.chirpboard.app.feature.recording.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes finalize-work ownership checks with the actions they guard.
 *
 * Recovery actions (recover/discard/keep) and the startup reconciler both decide
 * what to do based on whether the finalize worker owns a session. Without a shared
 * lock, the reconciler could enqueue finalize work right after an action's ownership
 * check returned "no work", letting the worker and the action operate on the same
 * files. Holding this lock across each check-and-act sequence closes that window.
 */
@Singleton
class RecordingFinalizeOwnershipLock
    @Inject
    constructor() {
        private val mutex = Mutex()

        suspend fun <T> withLock(action: suspend () -> T): T = mutex.withLock { action() }
    }
