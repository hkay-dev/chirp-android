package dev.chirpboard.app.core.reliability

import android.util.Log
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ReliabilityStage {
    RECORDING_START,
    RECORDING_STOP,
    PERSISTENCE_SAVE,
    QUEUE_ENQUEUE,
    TRANSCRIPTION,
    ENHANCEMENT
}

enum class ReliabilityOutcome {
    STARTED,
    SUCCESS,
    FAILURE,
    RECOVERED,
    SKIPPED
}

data class ReliabilityEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val correlationId: String,
    val recordingId: UUID?,
    val stage: ReliabilityStage,
    val outcome: ReliabilityOutcome,
    val reasonCode: String?,
    val message: String?
)

object ReliabilityEventLogger {
    private const val TAG = "ReliabilityEvent"
    private const val MAX_EVENTS = 250

    private val _events = MutableStateFlow<List<ReliabilityEvent>>(emptyList())
    val events: StateFlow<List<ReliabilityEvent>> = _events.asStateFlow()

    fun newCorrelationId(prefix: String = "rec"): String {
        return "$prefix-${UUID.randomUUID()}"
    }

    fun clear() {
        _events.value = emptyList()
    }

    fun log(
        stage: ReliabilityStage,
        outcome: ReliabilityOutcome,
        correlationId: String,
        recordingId: UUID? = null,
        reasonCode: String? = null,
        message: String? = null
    ) {
        val event = ReliabilityEvent(
            correlationId = correlationId,
            recordingId = recordingId,
            stage = stage,
            outcome = outcome,
            reasonCode = redactReason(reasonCode),
            message = redactMessage(message)
        )

        _events.update { existing ->
            (existing + event).takeLast(MAX_EVENTS)
        }

        try {
            Log.d(
                TAG,
                "${event.stage}:${event.outcome} corr=${event.correlationId} rec=${event.recordingId} reason=${event.reasonCode}"
            )
        } catch (_: Throwable) {
            // android.util.Log may be unavailable in local JVM tests.
        }
    }
}

internal fun redactReason(reason: String?): String? {
    return reason?.take(120)
}

internal fun redactMessage(message: String?): String? {
    if (message == null) return null

    val scrubbedPath = message.replace(Regex("""(/[\w.\-]+)+"""), "[path]")
    val scrubbedToken = scrubbedPath.replace(Regex("""[A-Za-z0-9_\-]{24,}"""), "[redacted]")
    return scrubbedToken.take(200)
}
