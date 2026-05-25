package dev.chirpboard.app.feature.recording.service

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.session.RecordingRecoveryStore
import dev.chirpboard.app.feature.recording.session.RecordingSessionJournal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

@HiltWorker
class RecordingFinalizeWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val stopOrchestrator: RecordingStopOrchestrator,
        private val sessionJournal: RecordingSessionJournal,
        private val recordingRepository: RecordingRepository,
        private val recoveryStore: RecordingRecoveryStore,
    ) : CoroutineWorker(appContext, workerParams) {
        override suspend fun doWork(): Result {
            val snapshot = StopSnapshot.fromWorkData(inputData)
            if (snapshot == null) {
                Log.e(TAG, "Missing finalize snapshot input")
                return Result.failure()
            }

            val sessionId =
                inputData.getString(RecordingFinalizeWorkKeys.INPUT_SESSION_ID)?.let { raw ->
                    runCatching { UUID.fromString(raw) }.getOrNull()
                }

            setForeground(buildRecordingFinalizeForegroundInfo(applicationContext))

            ReliabilityEventLogger.log(
                stage = ReliabilityStage.RECORDING_STOP,
                outcome = ReliabilityOutcome.STARTED,
                correlationId = snapshot.correlationId,
                recordingId = snapshot.recordingId,
                reasonCode = "background_finalize_started",
            )

            val result =
                try {
                    withContext(Dispatchers.IO) {
                        stopOrchestrator.persistAndQueueRecording(snapshot, sessionId)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Background finalize failed", e)
                    StopPersistenceResult.PersistenceFailed(
                        message = "Background finalize failed: ${e.message}",
                        cause = e,
                    )
                }

            RecordingFinalizeStopOutcomeApplier.apply(
                result = result,
                snapshot = snapshot,
                sessionId = sessionId,
                sessionJournal = sessionJournal,
                recordingRepository = recordingRepository,
            )
            recoveryStore.refresh()

            return when (result) {
                is StopPersistenceResult.PersistenceFailed -> Result.failure()
                StopPersistenceResult.NoAudioFile -> Result.failure()
                else -> Result.success()
            }
        }

        companion object {
            private const val TAG = "RecordingFinalizeWorker"
        }
    }
