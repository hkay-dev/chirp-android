package dev.chirpboard.app.feature.transcription

import dev.chirpboard.app.core.transcription.ManualRecoveryResult
import dev.chirpboard.app.core.transcription.RecoveryDiagnostics
import dev.chirpboard.app.core.transcription.RecoveryOwnershipState
import dev.chirpboard.app.core.transcription.GOOGLE_CLOUD_CHIRP_3_MAX_AUDIO_BYTES
import dev.chirpboard.app.core.transcription.GOOGLE_CLOUD_CHIRP_3_MAX_DURATION_MS
import dev.chirpboard.app.core.transcription.TranscriptionEngine
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingProcessingNoteCodes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

// I18N-06: the persisted marker strings are owned by the data module's typed classifier so
// producers and consumers can never drift; these aliases keep this module's call sites terse.
internal const val MANUAL_RECOVERY_PREFIX = RecordingProcessingNoteCodes.MANUAL_RECOVERY_PREFIX
internal const val RECOVERABLE_QUEUE_HANDOFF_PREFIX = RecordingProcessingNoteCodes.RECOVERABLE_QUEUE_HANDOFF_PREFIX
internal const val RECOVERABLE_STALE_TRANSCRIBING_PREFIX = RecordingProcessingNoteCodes.RECOVERABLE_STALE_TRANSCRIBING_PREFIX
internal const val RECOVERABLE_STALE_ENHANCING_PREFIX = RecordingProcessingNoteCodes.RECOVERABLE_STALE_ENHANCING_PREFIX

private val queueSchedulingMutex = Mutex()

/**
 * Keeps every in-process execution-token claim paired with the work request that owns it.
 * The mutex lives at module scope so manager calls and reconciliation passes use the same gate.
 */
internal suspend fun <T> withSerializedQueueScheduling(block: suspend () -> T): T =
    queueSchedulingMutex.withLock { block() }

/**
 * Oversized cloud recordings must run without a network constraint so the worker can start
 * offline and reroute them to the local engine before trying a cloud request.
 */
internal fun Recording.requiresNetworkForTranscription(): Boolean =
    transcriptionEngineId == TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3.id &&
        durationMs <= GOOGLE_CLOUD_CHIRP_3_MAX_DURATION_MS &&
        File(audioPath).length() <= GOOGLE_CLOUD_CHIRP_3_MAX_AUDIO_BYTES

internal data class ParsedRecoveryMetadata(
    val reason: String?,
    val lastAttemptEpochMs: Long?,
)

internal fun parseRecoveryMetadata(errorMessage: String?): ParsedRecoveryMetadata {
    if (errorMessage.isNullOrBlank()) {
        return ParsedRecoveryMetadata(reason = null, lastAttemptEpochMs = null)
    }

    val normalizedReason =
        errorMessage
            .removePrefix(RECOVERABLE_QUEUE_HANDOFF_PREFIX)
            .removePrefix(RECOVERABLE_STALE_TRANSCRIBING_PREFIX)
            .removePrefix(RECOVERABLE_STALE_ENHANCING_PREFIX)

    if (!normalizedReason.startsWith(MANUAL_RECOVERY_PREFIX)) {
        return ParsedRecoveryMetadata(reason = normalizedReason, lastAttemptEpochMs = null)
    }

    val payload = normalizedReason.removePrefix(MANUAL_RECOVERY_PREFIX)
    val recoveryAttemptMarker = "|attemptAt="
    val attemptIndex = payload.indexOf(recoveryAttemptMarker)
    if (attemptIndex < 0) {
        return ParsedRecoveryMetadata(reason = payload, lastAttemptEpochMs = null)
    }

    val reason = payload.substring(0, attemptIndex)
    val timestampRaw = payload.substring(attemptIndex + recoveryAttemptMarker.length)
    val timestamp = timestampRaw.toLongOrNull()

    return ParsedRecoveryMetadata(reason = reason, lastAttemptEpochMs = timestamp)
}

internal fun buildManualRecoveryMessage(reason: String): String = "$MANUAL_RECOVERY_PREFIX$reason|attemptAt=${System.currentTimeMillis()}"

internal fun blockedManualRecoveryResult(ownership: QueueOwnership): ManualRecoveryResult? =
    when (ownership) {
        QueueOwnership.ACTIVE -> ManualRecoveryResult.BLOCKED_ACTIVE_WORK
        QueueOwnership.INSPECTION_TIMEOUT -> ManualRecoveryResult.BLOCKED_OWNERSHIP_TIMEOUT
        QueueOwnership.MISSING_OR_TERMINAL -> null
    }

internal fun QueueOwnership.toRecoveryOwnershipState(): RecoveryOwnershipState =
    when (this) {
        QueueOwnership.ACTIVE -> RecoveryOwnershipState.ACTIVE
        QueueOwnership.MISSING_OR_TERMINAL -> RecoveryOwnershipState.MISSING_OR_TERMINAL
        QueueOwnership.INSPECTION_TIMEOUT -> RecoveryOwnershipState.INSPECTION_TIMEOUT
    }

internal enum class ReconciliationTrigger {
    STARTUP,
    PERIODIC,
}

internal enum class QueueOwnership {
    ACTIVE,
    MISSING_OR_TERMINAL,
    INSPECTION_TIMEOUT,
}

internal fun shouldRecoverStaleTranscribing(
    trigger: ReconciliationTrigger,
    createdAtEpochMs: Long,
    ownership: QueueOwnership,
    nowEpochMs: Long,
    staleThresholdMs: Long,
): Boolean {
    if (ownership == QueueOwnership.ACTIVE || ownership == QueueOwnership.INSPECTION_TIMEOUT) {
        return false
    }

    return trigger == ReconciliationTrigger.STARTUP ||
        (nowEpochMs - createdAtEpochMs) >= staleThresholdMs
}

internal fun shouldRecoverStaleEnhancing(
    trigger: ReconciliationTrigger,
    createdAtEpochMs: Long,
    ownership: QueueOwnership,
    nowEpochMs: Long,
    staleThresholdMs: Long,
): Boolean {
    if (ownership == QueueOwnership.ACTIVE || ownership == QueueOwnership.INSPECTION_TIMEOUT) {
        return false
    }

    return trigger == ReconciliationTrigger.STARTUP ||
        (nowEpochMs - createdAtEpochMs) >= staleThresholdMs
}

internal fun shouldRequeuePending(ownership: QueueOwnership): Boolean = ownership == QueueOwnership.MISSING_OR_TERMINAL

internal fun mergePendingRecordings(
    pendingTranscription: List<Recording>,
    pendingEnhancement: List<Recording>,
): List<Recording> =
    (pendingTranscription + pendingEnhancement)
        .sortedByDescending { it.createdAt }

/**
 * Change-detection key for the set of non-terminal recordings. Two passes with the same
 * signature describe the same queue, so reconciliation can be skipped between them and a
 * fixed poll replaced by observing this value. [isEmpty] is the gate for stopping the idle
 * safety-net timer. Equality is over the id set per status, so a status transition
 * (PENDING_TRANSCRIPTION -> TRANSCRIBING), an add, or a removal all produce a distinct value
 * — but a row sitting unchanged in TRANSCRIBING does not (that staleness is the safety-net's
 * job to catch).
 */
internal data class QueueWorkSignature(
    val pendingTranscription: Set<UUID>,
    val pendingEnhancement: Set<UUID>,
    val transcribing: Set<UUID>,
    val enhancing: Set<UUID>,
) {
    val isEmpty: Boolean
        get() =
            pendingTranscription.isEmpty() &&
                pendingEnhancement.isEmpty() &&
                transcribing.isEmpty() &&
                enhancing.isEmpty()

    companion object {
        fun of(
            pendingTranscription: List<Recording>,
            pendingEnhancement: List<Recording>,
            transcribing: List<Recording>,
            enhancing: List<Recording>,
        ): QueueWorkSignature =
            QueueWorkSignature(
                pendingTranscription = pendingTranscription.mapTo(mutableSetOf()) { it.id },
                pendingEnhancement = pendingEnhancement.mapTo(mutableSetOf()) { it.id },
                transcribing = transcribing.mapTo(mutableSetOf()) { it.id },
                enhancing = enhancing.mapTo(mutableSetOf()) { it.id },
            )
    }
}
