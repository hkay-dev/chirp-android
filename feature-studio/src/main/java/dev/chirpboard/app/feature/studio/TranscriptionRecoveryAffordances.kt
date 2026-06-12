package dev.chirpboard.app.feature.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.transcription.RecoveryOwnershipState
import dev.chirpboard.app.core.ui.R as CoreR
import dev.chirpboard.app.data.model.isWaitingForSpeechModel

@Composable
fun TranscriptionRecoverySection(
    recoveryActions: TranscriptionRecoveryActionsUi,
    diagnostics: RecoveryDiagnosticsUi,
    onRecoverPending: () -> Unit,
    onRecoverEnhancing: () -> Unit,
    onRetranscribeFromEnhancing: () -> Unit,
    onRetryFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (recoveryActions.showPendingRecovery) {
            PendingRecoveryAffordance(
                diagnostics = diagnostics,
                actionsEnabled = recoveryActions.actionsEnabled,
                onRecoverPending = onRecoverPending,
            )
        }

        if (recoveryActions.showEnhancementRecovery) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EnhancingRecoveryActions(
                    actionsEnabled = recoveryActions.actionsEnabled,
                    showRetranscribe = recoveryActions.showRetranscribeFromEnhancing,
                    onRecoverEnhancing = onRecoverEnhancing,
                    onRetranscribe = onRetranscribeFromEnhancing,
                )
            }
        }

        if (recoveryActions.showFailedRetry) {
            FilledTonalButton(
                onClick = onRetryFailed,
                enabled = recoveryActions.actionsEnabled,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(CoreR.string.rec_retry_transcription))
            }
        }
    }
}

@Composable
fun EnhancingRecoveryActions(
    actionsEnabled: Boolean,
    showRetranscribe: Boolean = true,
    onRecoverEnhancing: () -> Unit,
    onRetranscribe: () -> Unit,
) {
    TextButton(
        onClick = onRecoverEnhancing,
        enabled = actionsEnabled,
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.testTag(TranscriptionRecoveryTestTags.EnhancingRecoverButton),
    ) {
        Icon(
            imageVector = Icons.Rounded.Build,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(stringResource(CoreR.string.rec_recover), style = MaterialTheme.typography.labelMedium)
    }
    if (showRetranscribe) {
        TextButton(
            onClick = onRetranscribe,
            enabled = actionsEnabled,
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.testTag(TranscriptionRecoveryTestTags.EnhancingRetranscribeButton),
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.rec_retranscribe), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun PendingRecoveryAffordance(
    diagnostics: RecoveryDiagnosticsUi,
    actionsEnabled: Boolean,
    onRecoverPending: () -> Unit,
) {
    FilledTonalButton(
        onClick = onRecoverPending,
        enabled = actionsEnabled,
        modifier = Modifier.testTag(TranscriptionRecoveryTestTags.PendingRecoverButton),
    ) {
        Icon(
            imageVector = Icons.Rounded.Refresh,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(stringResource(R.string.rec_recover_queue))
    }

    Spacer(modifier = Modifier.height(4.dp))
    RecoveryDiagnosticsSection(
        diagnostics = diagnostics,
        actionsEnabled = actionsEnabled,
    )
}

@Composable
private fun RecoveryDiagnosticsSection(
    diagnostics: RecoveryDiagnosticsUi,
    actionsEnabled: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // I18N-12: reliability reason codes and enum constants are developer telemetry; the
        // user-facing block maps them to plain language.
        Text(
            text =
                stringResource(
                    R.string.rec_recovery_latest_reason,
                    recoveryReasonDisplayText(diagnostics.latestReason),
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val context = LocalContext.current
        val attempt =
            diagnostics.lastAttemptEpochMs?.let { epochMs ->
                // Relative date WITH a time of day — "Today" alone can't tell a stuck retry
                // from a fresh one.
                DateUtils
                    .getRelativeDateTimeString(
                        context,
                        epochMs,
                        DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.WEEK_IN_MILLIS,
                        0,
                    ).toString()
            } ?: stringResource(R.string.rec_recovery_unknown)
        Text(
            text = stringResource(R.string.rec_recovery_last_attempt, attempt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text =
                stringResource(
                    R.string.rec_recovery_ownership,
                    recoveryOwnershipDisplayText(diagnostics.ownership),
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!actionsEnabled) {
            Text(
                text = stringResource(R.string.rec_recovery_actions_disabled),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * I18N-12: map machine reason codes to short human copy. Only snake_case machine codes are
 * humanized; anything else (raw exception text persisted by legacy rows) falls back to the
 * generic failure line so developer diagnostics never reach the screen (I18N-05).
 */
@Composable
private fun recoveryReasonDisplayText(reason: String?): String =
    when {
        reason == null -> stringResource(R.string.rec_recovery_no_reason)
        isWaitingForSpeechModel(reason) ->
            stringResource(R.string.rec_recovery_reason_model_missing)
        reason.startsWith("worker_exception") ->
            stringResource(R.string.rec_recovery_reason_failed_unexpectedly)
        reason.contains("stale") ->
            stringResource(R.string.rec_recovery_reason_stalled)
        reason.contains("queue_handoff") || reason.contains("interrupted") ->
            stringResource(R.string.rec_recovery_reason_interrupted)
        MACHINE_REASON_CODE.matches(reason) -> reason.replace('_', ' ').replaceFirstChar { it.uppercase() }
        else -> stringResource(R.string.rec_recovery_reason_failed_unexpectedly)
    }

private val MACHINE_REASON_CODE = Regex("[a-z0-9_]+")

@Composable
private fun recoveryOwnershipDisplayText(ownership: RecoveryOwnershipState): String =
    when (ownership) {
        RecoveryOwnershipState.ACTIVE -> stringResource(R.string.rec_recovery_ownership_active)
        RecoveryOwnershipState.MISSING_OR_TERMINAL -> stringResource(R.string.rec_recovery_ownership_idle)
        RecoveryOwnershipState.INSPECTION_TIMEOUT -> stringResource(R.string.rec_recovery_ownership_unknown)
    }
