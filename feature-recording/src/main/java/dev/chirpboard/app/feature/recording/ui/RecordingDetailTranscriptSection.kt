package dev.chirpboard.app.feature.recording.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.util.formatRelative
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.core.R as CoreR
import dev.chirpboard.app.feature.recording.R
import dev.chirpboard.app.feature.recording.ui.components.ContentSection
import java.util.Date

@Composable
internal fun RecordingDetailTranscriptSection(
    recording: Recording,
    transcript: Transcript?,
    recoveryDiagnostics: RecoveryDiagnosticsUi,
    recoveryActions: DetailRecoveryActions,
    onShareAudio: () -> Unit,
    onShareTranscript: () -> Unit,
    onShareBoth: () -> Unit,
    onRetryTranscription: () -> Unit,
    onRecoverEnhancing: () -> Unit,
    onRetranscribe: () -> Unit,
    onRecoverPending: () -> Unit,
) {
    val hasTranscript = transcript != null
    var showShareMenu by remember { mutableStateOf(false) }

    AnimatedContent(
        targetState = Pair(hasTranscript, recording.status),
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "transcript-content",
    ) { (hasText, status) ->
        when {
            hasText -> {
                ContentSection(
                    title = stringResource(R.string.rec_transcript),
                    action =
                        if (status == RecordingStatus.COMPLETED || status == RecordingStatus.ENHANCING) {
                            {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (status == RecordingStatus.COMPLETED) {
                                        Box {
                                            IconButton(
                                                onClick = { showShareMenu = true },
                                                // Removed modifier = Modifier.size(36.dp) since we want minimum touch target of 48.dp
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Share,
                                                    contentDescription = stringResource(CoreR.string.desc_share),
                                                    modifier = Modifier.size(20.dp),
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            }

                                            DropdownMenu(
                                                expanded = showShareMenu,
                                                onDismissRequest = { showShareMenu = false },
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(CoreR.string.rec_share_audio)) },
                                                    onClick = {
                                                        showShareMenu = false
                                                        onShareAudio()
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.AudioFile, contentDescription = null)
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(CoreR.string.rec_share_transcript)) },
                                                    onClick = {
                                                        showShareMenu = false
                                                        onShareTranscript()
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.Description, contentDescription = null)
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(CoreR.string.rec_share_both)) },
                                                    onClick = {
                                                        showShareMenu = false
                                                        onShareBoth()
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Default.FolderZip, contentDescription = null)
                                                    },
                                                )
                                            }
                                        }

                                        TextButton(
                                            onClick = onRetryTranscription,
                                            contentPadding = PaddingValues(horizontal = 8.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(stringResource(R.string.rec_rerun), style = MaterialTheme.typography.labelMedium)
                                        }
                                    }

                                    if (status == RecordingStatus.ENHANCING) {
                                        EnhancingRecoveryActions(
                                            actionsEnabled = recoveryActions.actionsEnabled,
                                            onRecoverEnhancing = onRecoverEnhancing,
                                            onRetranscribe = onRetranscribe,
                                        )
                                    }
                                }
                            }
                        } else {
                            null
                        },
                ) {
                    val text = transcript?.effectiveText ?: ""
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (status == RecordingStatus.ENHANCING) {
                        Spacer(modifier = Modifier.height(12.dp))
                        RecoveryDiagnosticsSection(
                            diagnostics = recoveryDiagnostics,
                            actionsEnabled = recoveryActions.actionsEnabled,
                        )
                    }
                }
            }

            status == RecordingStatus.PENDING_TRANSCRIPTION ||
                status == RecordingStatus.TRANSCRIBING -> {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(32.dp)
                            .animateContentSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text =
                                if (status == RecordingStatus.TRANSCRIBING) {
                                    stringResource(R.string.rec_transcribing)
                                } else {
                                    stringResource(R.string.rec_waiting_for_transcription)
                                },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    if (status == RecordingStatus.TRANSCRIBING) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(2.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                    }

                    if (status == RecordingStatus.PENDING_TRANSCRIPTION) {
                        PendingRecoveryAffordance(
                            diagnostics = recoveryDiagnostics,
                            actionsEnabled = recoveryActions.actionsEnabled,
                            onRecoverPending = onRecoverPending,
                        )
                    }
                }
            }

            status == RecordingStatus.FAILED -> {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(R.string.rec_transcription_failed),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    recording.errorMessage?.let { errorMessage ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    FilledTonalButton(
                        onClick = onRetryTranscription,
                        enabled = recoveryActions.actionsEnabled,
                        colors =
                            ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
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

            else -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.rec_no_transcript_available),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal object RecordingDetailRecoveryTestTags {
    const val PendingRecoverButton = "pending_recover_button"
    const val EnhancingRecoverButton = "enhancing_recover_button"
    const val EnhancingRetranscribeButton = "enhancing_retranscribe_button"
}

@Composable
internal fun EnhancingRecoveryActions(
    actionsEnabled: Boolean,
    onRecoverEnhancing: () -> Unit,
    onRetranscribe: () -> Unit,
) {
    TextButton(
        onClick = onRecoverEnhancing,
        enabled = actionsEnabled,
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.testTag(RecordingDetailRecoveryTestTags.EnhancingRecoverButton),
    ) {
        Icon(
            imageVector = Icons.Default.Build,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(stringResource(R.string.rec_recover), style = MaterialTheme.typography.labelMedium)
    }
    TextButton(
        onClick = onRetranscribe,
        enabled = actionsEnabled,
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.testTag(RecordingDetailRecoveryTestTags.EnhancingRetranscribeButton),
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

@Composable
internal fun PendingRecoveryAffordance(
    diagnostics: RecoveryDiagnosticsUi,
    actionsEnabled: Boolean,
    onRecoverPending: () -> Unit,
) {
    Spacer(modifier = Modifier.height(16.dp))
    FilledTonalButton(
        onClick = onRecoverPending,
        enabled = actionsEnabled,
        modifier = Modifier.testTag(RecordingDetailRecoveryTestTags.PendingRecoverButton),
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(stringResource(R.string.rec_recover_queue))
    }

    Spacer(modifier = Modifier.height(12.dp))
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
