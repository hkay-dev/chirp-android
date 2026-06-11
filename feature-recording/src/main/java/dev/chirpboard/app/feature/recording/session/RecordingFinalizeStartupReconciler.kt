package dev.chirpboard.app.feature.recording.session

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.service.RecordingFinalizeWorkRequest
import dev.chirpboard.app.feature.recording.service.StopSnapshot
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class RecordingFinalizeStartupReconciler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val sessionJournal: RecordingSessionJournal,
        private val recordingRepository: RecordingRepository,
        private val ownershipLock: RecordingFinalizeOwnershipLock,
    ) {
        suspend fun reconcilePendingFinalizations(): Set<UUID> {
            val stoppingSessions =
                sessionJournal.loadAllEntries().filter { entry ->
                    entry.state == SessionJournalState.STOPPING && entry.recordingId != null
                }
            val enqueuedSessionIds = mutableSetOf<UUID>()

            stoppingSessions.forEach { entry ->
                val recordingId = entry.recordingId ?: return@forEach
                // The ownership lock serializes this check-and-enqueue with user-driven
                // recovery actions, so an action's "no finalize work" verdict cannot be
                // invalidated by an enqueue happening here a moment later.
                val handled = ownershipLock.withLock { reconcileEntry(entry, recordingId) }
                if (handled) {
                    enqueuedSessionIds += entry.sessionId
                }
            }
            return enqueuedSessionIds
        }

        private suspend fun reconcileEntry(
            entry: RecordingSessionEntry,
            recordingId: UUID,
        ): Boolean {
            // Query the work state BEFORE reading the recording row: a worker that
            // completes in between leaves the row finalized, so the row re-read below
            // cannot pair a stale RECORDING status with already-finished work and
            // enqueue a redundant finalize.
            val hasUnfinishedWork =
                try {
                    RecordingFinalizeWorkRequest.hasUnfinishedWork(context, recordingId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Unknown finalize state (query failed or timed out): fail closed for
                    // this pass, like RecordingSessionRecovery does. Report the session as
                    // handled so synchronous recovery does not race a possibly live
                    // worker; the journal entry survives and the next pass retries.
                    Log.w(TAG, "Could not query finalize work for ${entry.sessionId}; skipping this pass", e)
                    return true
                }
            if (hasUnfinishedWork) {
                // The worker already owns this session; report it as handled so callers
                // exclude it from synchronous recovery instead of double-finalizing.
                return true
            }
            val recording = recordingRepository.getRecording(recordingId) ?: return false
            if (recording.status != RecordingStatus.RECORDING) {
                return false
            }
            val snapshot = StopSnapshot.fromSessionEntry(entry)
            RecordingFinalizeWorkRequest.enqueue(
                context = context,
                snapshot = snapshot,
                sessionId = entry.sessionId,
            )
            return true
        }

        private companion object {
            private const val TAG = "FinalizeStartupRecon"
        }
    }
