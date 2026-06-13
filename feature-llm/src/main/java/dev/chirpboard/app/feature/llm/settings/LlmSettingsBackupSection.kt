package dev.chirpboard.app.feature.llm.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.chirpboard.app.core.ui.components.SettingsSectionHeader
import dev.chirpboard.app.core.ui.theme.ChirpSpacing
import dev.chirpboard.app.feature.llm.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LlmSettingsBackupSection(
    configuredKeyCount: Int,
    isSecureStorageAvailable: Boolean,
    backupMessage: LlmSettingsViewModel.StatusMessage?,
    onDismissBackupMessage: () -> Unit,
    onStartBackup: () -> Unit,
    onStartRestore: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Small),
    ) {
        SettingsSectionHeader(title = stringResource(R.string.llm_backup_section_title))

        Text(
            text = stringResource(R.string.llm_backup_section_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ChirpSpacing.ScreenHorizontal),
        )

        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ChirpSpacing.ScreenHorizontal),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                modifier = Modifier.padding(ChirpSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Medium),
            ) {
                Text(
                    text =
                        pluralStringResource(
                            R.plurals.llm_backup_saved_keys_summary,
                            configuredKeyCount,
                            configuredKeyCount,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(ChirpSpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Small),
                ) {
                    FilledTonalButton(
                        onClick = onStartBackup,
                        enabled = isSecureStorageAvailable && configuredKeyCount > 0,
                    ) {
                        Text(stringResource(R.string.llm_backup_export))
                    }

                    OutlinedButton(
                        onClick = onStartRestore,
                        enabled = isSecureStorageAvailable,
                    ) {
                        Text(stringResource(R.string.llm_backup_import))
                    }
                }

                Text(
                    text = stringResource(R.string.llm_backup_security_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        backupMessage?.let { message ->
            BackupStatusBanner(
                message = message,
                onDismiss = onDismissBackupMessage,
            )
        }
    }
}

@Composable
private fun BackupStatusBanner(
    message: LlmSettingsViewModel.StatusMessage,
    onDismiss: () -> Unit,
) {
    val containerColor =
        when (message) {
            is LlmSettingsViewModel.StatusMessage.Success -> MaterialTheme.colorScheme.primaryContainer
            is LlmSettingsViewModel.StatusMessage.Error -> MaterialTheme.colorScheme.errorContainer
        }
    val contentColor =
        when (message) {
            is LlmSettingsViewModel.StatusMessage.Success -> MaterialTheme.colorScheme.onPrimaryContainer
            is LlmSettingsViewModel.StatusMessage.Error -> MaterialTheme.colorScheme.onErrorContainer
        }

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ChirpSpacing.ScreenHorizontal, vertical = ChirpSpacing.Small),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        onClick = onDismiss,
    ) {
        Column(
            modifier = Modifier.padding(ChirpSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = stringResource(R.string.llm_connection_dismiss),
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.8f),
            )
        }
    }
}
