package dev.chirpboard.app.feature.recording.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingSessionHeartbeat
    @Inject
    constructor(
        private val sessionJournal: RecordingSessionJournal,
    ) {
        fun start(
            scope: CoroutineScope,
            sessionIdProvider: () -> UUID?,
            activeFileProvider: () -> File?,
        ): Job =
            scope.launch {
                while (isActive) {
                    delay(30_000)
                    // Hop the File.exists/length stat plus the journal read-modify-write
                    // off the caller's dispatcher (the service runs this on Main): the
                    // journal mutation takes journalLock, which directory scans on IO
                    // threads also hold, so doing it on Main would block the recording UI
                    // for the duration of a reconcile/cleanup pass.
                    withContext(Dispatchers.IO) {
                        val sessionId = sessionIdProvider() ?: return@withContext
                        val bytes = activeFileProvider()?.takeIf { it.exists() }?.length() ?: 0L
                        sessionJournal.updateHeartbeat(sessionId, bytes)
                    }
                }
            }
    }
