package dev.chirpboard.app.feature.transcription

import dev.chirpboard.app.core.transcription.ManualRecoveryResult
import dev.chirpboard.app.core.transcription.RecoveryDiagnostics
import dev.chirpboard.app.core.transcription.RecoveryOwnershipState
import dev.chirpboard.app.data.entity.Recording

internal const val MANUAL_RECOVERY_PREFIX = "manual_recovery:"
internal const val RECOVERABLE_QUEUE_HANDOFF_PREFIX = "recoverable_queue_handoff:"
internal const val RECOVERABLE_STALE_TRANSCRIBING_PREFIX = "recoverable_stale_transcribing:"
internal const val RECOVERABLE_STALE_ENHANCING_PREFIX = "recoverable_stale_enhancing:"

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
