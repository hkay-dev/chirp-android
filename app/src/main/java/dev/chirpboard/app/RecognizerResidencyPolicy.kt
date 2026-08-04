package dev.chirpboard.app

import android.util.Log
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Pressure-only release gate for every resident local recognizer.
 *
 * There is deliberately no timer. IME hides, app switches, completed dictations, and elapsed idle
 * time cannot unload a model. Confirmed system memory or severe thermal pressure may release only
 * after capture, queued transcription, and active native decode leases are all clear.
 */
@Singleton
class RecognizerResidencyPolicy
    @Inject
    constructor(
        private val recordingStateManager: RecordingStateManager,
        private val recordingRepository: dagger.Lazy<RecordingRepository>,
    ) {
        suspend fun releaseForConfirmedPressure(reason: String): PressureReleaseResult {
            if (isExternallyBusy()) {
                return PressureReleaseResult(sherpa = RecognizerReleaseDecision.EXTERNALLY_BUSY, ggufReleased = false)
            }
            val sherpa =
                RecognizerManager.releaseForPressureIfUnused(isExternallyBusy = { false })
            val ggufReleased = GgufRecognizerManager.releaseIfUnused()
            return PressureReleaseResult(sherpa, ggufReleased).also {
                Log.i(TAG, "Pressure release ($reason) -> $it")
            }
        }

        /** Queue read failures keep resident models, which is the safe direction. */
        internal suspend fun isExternallyBusy(): Boolean {
            if (recordingStateManager.state.value !is RecordingState.Idle) return true
            val repository = recordingRepository.get()
            val transcribing = repository.getRecordingsByStatus(RecordingStatus.TRANSCRIBING).first()
            if (transcribing.errorMessage != null || transcribing.value.isNotEmpty()) return true
            val pending = repository.getRecordingsByStatus(RecordingStatus.PENDING_TRANSCRIPTION).first()
            return pending.errorMessage != null || pending.value.isNotEmpty()
        }

        private companion object {
            const val TAG = "RecognizerResidency"
        }
    }

data class PressureReleaseResult(
    val sherpa: RecognizerReleaseDecision,
    val ggufReleased: Boolean,
)
