package dev.chirpboard.app.feature.recording.session

import dev.chirpboard.app.feature.recording.session.validation.RecordingFileValidator
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
                    // for the duration of a reconcile/cleanup pass. Mirrors the sibling
                    // RecordingCheckpointScheduler.
                    withContext(Dispatchers.IO) {
                        val sessionId = sessionIdProvider() ?: return@withContext
                        val bytes = activeFileProvider()?.takeIf { it.exists() }?.length() ?: 0L
                        sessionJournal.updateHeartbeat(sessionId, bytes)
                    }
                }
            }
    }

@Singleton
class RecordingCheckpointScheduler
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
                    delay(RecordingSessionJournal.CHECKPOINT_INTERVAL_MS)
                    withContext(Dispatchers.IO) {
                        val file = activeFileProvider()?.takeIf { it.exists() } ?: return@withContext
                        val sessionId = sessionIdProvider() ?: return@withContext
                        val checkpoint = File(RecordingFileValidator.checkpointPathFor(file.absolutePath))
                        runCatching {
                            file.copyTo(checkpoint, overwrite = true)
                            sessionJournal.updateCheckpoint(sessionId, checkpoint.absolutePath, file.length())
                        }
                    }
                }
            }
    }
