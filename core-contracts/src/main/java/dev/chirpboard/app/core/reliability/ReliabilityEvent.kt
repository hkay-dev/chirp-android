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

    /**
     * Returns a [ReliabilityEventScope] bound to a single [stage]/[correlationId]
     * (and optional [recordingId]/[reasonPrefix]) so a call site can emit STARTED/
     * SUCCESS/FAILURE/SKIPPED/RECOVERED events as one-liners instead of re-spelling the
     * stage, outcome, and correlation id on every [log] call.
     *
     * The bound scope only fills in the repeated fields and standardizes how a reason
     * code is formed: when [reasonPrefix] is non-null the emitted `reasonCode` is
     * `"${reasonPrefix}_$reason"`, otherwise it is the [reason] verbatim. The resulting
     * [ReliabilityEvent] is identical to one produced by calling [log] directly — this is
     * a deduplication helper, not a behavior change.
     */
    fun scoped(
        stage: ReliabilityStage,
        correlationId: String,
        recordingId: UUID? = null,
        reasonPrefix: String? = null,
    ): ReliabilityEventScope = ReliabilityEventScope(stage, correlationId, recordingId, reasonPrefix)

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

/**
 * A reliability logger bound to a single stage/correlationId/recordingId (and optional
 * reason prefix). Each emit method forwards to [ReliabilityEventLogger.log] with the bound
 * fields filled in, so the emitted [ReliabilityEvent] is identical to a direct [log] call;
 * the only added behavior is standardized reason-code formation via [reasonCodeFor].
 *
 * Obtain one with [ReliabilityEventLogger.scoped].
 */
class ReliabilityEventScope internal constructor(
    private val stage: ReliabilityStage,
    private val correlationId: String,
    private val recordingId: UUID?,
    private val reasonPrefix: String?,
) {
    /** Combines the bound [reasonPrefix] (if any) with a per-event [reason] suffix. */
    private fun reasonCodeFor(reason: String): String =
        reasonPrefix?.let { "${it}_$reason" } ?: reason

    fun started(reason: String, message: String? = null) =
        emit(ReliabilityOutcome.STARTED, reason, message)

    fun success(reason: String, message: String? = null) =
        emit(ReliabilityOutcome.SUCCESS, reason, message)

    fun failure(reason: String, message: String? = null) =
        emit(ReliabilityOutcome.FAILURE, reason, message)

    /** Convenience overload that records a [throwable]'s message on the failure event. */
    fun failure(reason: String, throwable: Throwable?) =
        emit(ReliabilityOutcome.FAILURE, reason, throwable?.message)

    fun skipped(reason: String, message: String? = null) =
        emit(ReliabilityOutcome.SKIPPED, reason, message)

    fun recovered(reason: String, message: String? = null) =
        emit(ReliabilityOutcome.RECOVERED, reason, message)

    private fun emit(outcome: ReliabilityOutcome, reason: String, message: String?) {
        ReliabilityEventLogger.log(
            stage = stage,
            outcome = outcome,
            correlationId = correlationId,
            recordingId = recordingId,
            reasonCode = reasonCodeFor(reason),
            message = message,
        )
    }
}

private val PATH_REGEX = Regex("""(/[\w.\-]+)+""")
private val TOKEN_REGEX = Regex("""[A-Za-z0-9_\-]{24,}""")

internal fun redactReason(reason: String?): String? {
    return reason?.take(120)
}

internal fun redactMessage(message: String?): String? {
    if (message == null) return null

    val scrubbedPath = message.replace(PATH_REGEX, "[path]")
    val scrubbedToken = scrubbedPath.replace(TOKEN_REGEX, "[redacted]")
    return scrubbedToken.take(200)
}
