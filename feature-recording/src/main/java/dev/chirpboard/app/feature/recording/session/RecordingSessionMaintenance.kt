package dev.chirpboard.app.feature.recording.session

import android.util.Log
import kotlinx.coroutines.CancellationException
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
            durabilityCheckpoint: () -> Boolean = { true },
        ): Job =
            scope.launch {
                while (isActive) {
                    delay(30_000)
                    // Hop the File.exists/length stat plus the journal read-modify-write
                    // off the caller's dispatcher (the service runs this on Main): the
                    // journal mutation takes journalLock, which directory scans on IO
                    // threads also hold, so doing it on Main would block the recording UI
                    // for the duration of a reconcile/cleanup pass.
                    checkpointOnce(sessionIdProvider, activeFileProvider, durabilityCheckpoint)
                }
            }

        internal suspend fun checkpointOnce(
            sessionIdProvider: () -> UUID?,
            activeFileProvider: () -> File?,
            durabilityCheckpoint: () -> Boolean = { true },
        ) {
            try {
                withContext(Dispatchers.IO) {
                    val sessionId = sessionIdProvider() ?: return@withContext
                    if (!durabilityCheckpoint()) {
                        Log.w(TAG, "Live recording durability checkpoint raced a segment transition")
                    }
                    val bytes = activeFileProvider()?.takeIf { it.exists() }?.length() ?: 0L
                    sessionJournal.updateHeartbeat(sessionId, bytes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A transient stat, sync, or journal failure must not permanently kill
                // the heartbeat. The next loop retries and capture remains authoritative.
                Log.w(TAG, "Recording heartbeat checkpoint failed", e)
            }
        }

        private companion object {
            const val TAG = "RecordingHeartbeat"
        }
    }
