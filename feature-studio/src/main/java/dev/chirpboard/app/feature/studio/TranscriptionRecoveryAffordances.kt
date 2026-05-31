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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.util.formatRelative
import java.util.Date

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
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.rec_retry_transcription_cap))
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
            imageVector = Icons.Default.Build,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(stringResource(R.string.rec_recover), style = MaterialTheme.typography.labelMedium)
    }
    if (showRetranscribe) {
        TextButton(
            onClick = onRetranscribe,
            enabled = actionsEnabled,
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.testTag(TranscriptionRecoveryTestTags.EnhancingRetranscribeButton),
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
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
            imageVector = Icons.Default.Refresh,
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
        Text(
            text =
                stringResource(
                    R.string.rec_recovery_latest_reason,
                    diagnostics.latestReason ?: stringResource(R.string.rec_recovery_no_reason),
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val attempt =
            diagnostics.lastAttemptEpochMs?.let { Date(it).formatRelative() }
                ?: stringResource(R.string.rec_recovery_unknown)
        Text(
            text = stringResource(R.string.rec_recovery_last_attempt, attempt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = stringResource(R.string.rec_recovery_ownership, diagnostics.ownership.name),
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
